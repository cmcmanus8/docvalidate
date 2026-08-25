package com.docvalidate.service.application;

import com.docvalidate.service.config.DocValidateProperties;
import com.docvalidate.service.domain.Document;
import com.docvalidate.service.domain.IllegalStateTransitionException;
import com.docvalidate.service.domain.ValidationRequest;
import com.docvalidate.service.domain.ValidationStatus;
import com.docvalidate.service.persistence.ValidationRequestRepository;
import com.docvalidate.service.storage.DocumentStorage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationService {

    private final ValidationRequestRepository requests;
    private final DocumentStorage storage;
    private final DocValidateProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ValidationService(ValidationRequestRepository requests, DocumentStorage storage,
                             DocValidateProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.storage = storage;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Deliberately not {@code @Transactional}. The insert has to be able to fail and be
     * rolled back on its own before the recovery read runs: inside one transaction, a
     * unique-violation leaves Postgres refusing every later statement, so the read that
     * is meant to find the winning row would fail too.
     */
    public CreateResult create(String idempotencyKey) {
        return create(idempotencyKey, null, null);
    }

    public CreateResult create(String idempotencyKey, String declaredFilename, String declaredContentType) {
        if (idempotencyKey != null) {
            var existing = requests.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new CreateResult(existing.get(), true);
            }
        }
        ValidationRequest request = ValidationRequest.create(
                idempotencyKey, declaredFilename, declaredContentType, clock.instant(), properties.uploadWindow());
        try {
            return new CreateResult(requests.saveAndFlush(request), false);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey == null) {
                // Nothing to recover by: a key-less insert can only collide on the
                // primary key, which is a genuine fault rather than a replay.
                throw e;
            }
            // Two concurrent calls with the same key: the loser reads the winner's row.
            ValidationRequest winner = requests.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return new CreateResult(winner, true);
        }
    }

    @Transactional
    /**
     * @param filename from the upload's Content-Disposition, or null to fall back to whatever
     *                 the request declared when it was created.
     */
    public UploadOutcome upload(UUID requestId, String filename, String contentType, byte[] content) {
        // Identity of the request before size: an oversized upload to an id that does not
        // exist is a 404, not a 413. The filter has already rejected anything that declared
        // an oversized Content-Length, so this only catches a chunked body.
        ValidationRequest request = requests.findById(requestId)
                .orElseThrow(() -> new ValidationNotFoundException(requestId));

        if (content.length > properties.maxUploadSize().toBytes()) {
            throw new PayloadTooLargeException(properties.maxUploadSize().toBytes());
        }

        Instant now = clock.instant();
        String digest = sha256(content);

        // Checked in every status, not only QUEUED. Once the request holds a document, the
        // same bytes are a replay whatever the worker has done since - which is what makes
        // retrying a timed-out upload safe, rather than safe for about a second.
        Optional<String> held = request.getDocument().map(Document::getSha256);
        if (held.isPresent()) {
            if (held.get().equals(digest)) {
                return UploadOutcome.ALREADY_ACCEPTED;
            }
            throw new ContentMismatchException(requestId);
        }

        if (request.isExpiredAt(now)) {
            // Returned rather than thrown: an exception here would roll back the very
            // transition it is reporting, leaving the request PENDING_UPLOAD forever.
            request.expire(now);
            return UploadOutcome.EXPIRED;
        }

        // The lifecycle answers before the request body does, so an upload to a terminal
        // request is a 409 rather than a complaint about its headers.
        if (request.getStatus() != ValidationStatus.PENDING_UPLOAD) {
            throw new IllegalStateTransitionException(requestId, request.getStatus(), ValidationStatus.QUEUED);
        }

        String resolvedFilename = filename != null ? filename
                : request.getDeclaredFilename().orElseThrow(MissingFilenameException::new);

        // The declaration is a promise about the bytes, so breaking it is the caller's error
        // rather than something to silently overwrite.
        request.getDeclaredContentType()
                .filter(declared -> !declared.equals(contentType))
                .ifPresent(declared -> {
                    throw new DeclaredTypeMismatchException(requestId, declared, contentType);
                });

        // Written before the transition so a storage failure leaves the request
        // still awaiting an upload rather than QUEUED with nothing behind it.
        String storageKey = storage.store(requestId, resolvedFilename, content);
        request.attachDocument(resolvedFilename, contentType, content.length, digest, storageKey, now);

        // The listener is AFTER_COMMIT, so nothing reaches the broker for a transaction
        // that later rolls back.
        events.publishEvent(new ValidationQueuedEvent(requestId));
        return UploadOutcome.ACCEPTED;
    }

    @Transactional(readOnly = true)
    public ValidationRequest get(UUID requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> new ValidationNotFoundException(requestId));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}

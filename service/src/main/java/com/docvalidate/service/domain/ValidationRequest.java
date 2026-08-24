package com.docvalidate.service.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root. Every status change goes through {@link #transitionTo}, so an
 * invalid lifecycle move fails here rather than in whichever service happened to
 * forget the check.
 */
@Entity
@Table(name = "validation_request")
public class ValidationRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ValidationStatus status;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToOne(mappedBy = "request", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Document document;

    @OneToOne(mappedBy = "request", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private ValidationResult result;

    protected ValidationRequest() {
    }

    public static ValidationRequest create(String idempotencyKey, Instant now, Duration uploadWindow) {
        ValidationRequest request = new ValidationRequest();
        request.id = UUID.randomUUID();
        request.status = ValidationStatus.PENDING_UPLOAD;
        request.idempotencyKey = idempotencyKey;
        request.createdAt = now;
        request.updatedAt = now;
        request.expiresAt = now.plus(uploadWindow);
        return request;
    }

    public void transitionTo(ValidationStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(id, status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    /** Accepts the uploaded bytes and queues the request for processing. */
    public void attachDocument(String filename, String contentType, long sizeBytes,
                               String sha256, String storageKey, Instant now) {
        transitionTo(ValidationStatus.QUEUED, now);
        this.document = new Document(this, filename, contentType, sizeBytes, sha256, storageKey, now);
    }

    public void complete(Map<String, Object> extractedFields, Instant now) {
        transitionTo(ValidationStatus.COMPLETED, now);
        this.result = new ValidationResult(this, Verdict.VALID, null, extractedFields, now);
    }

    /**
     * The verdict is a parameter because FAILED covers two different things: a document
     * we read and rejected, and a document we never managed to judge. Telling a caller
     * their valid PDF is INVALID because our storage was unreachable would be a lie.
     */
    public void fail(Verdict verdict, String reason, Instant now) {
        transitionTo(ValidationStatus.FAILED, now);
        this.result = new ValidationResult(this, verdict, reason, Map.of(), now);
    }

    public void expire(Instant now) {
        transitionTo(ValidationStatus.EXPIRED, now);
    }

    public boolean isExpiredAt(Instant now) {
        return status == ValidationStatus.PENDING_UPLOAD && !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public ValidationStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getVersion() {
        return version;
    }

    public Optional<Document> getDocument() {
        return Optional.ofNullable(document);
    }

    public Optional<ValidationResult> getResult() {
        return Optional.ofNullable(result);
    }
}

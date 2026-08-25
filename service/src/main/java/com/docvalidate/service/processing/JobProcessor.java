package com.docvalidate.service.processing;

import com.docvalidate.service.domain.Document;
import com.docvalidate.service.domain.ValidationRequest;
import com.docvalidate.service.domain.Verdict;
import com.docvalidate.service.messaging.ValidationJob;
import com.docvalidate.service.persistence.ValidationRequestRepository;
import com.docvalidate.service.storage.DocumentStorage;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final ValidationRequestRepository requests;
    private final DocumentStorage storage;
    private final DocumentValidator validator;
    private final Clock clock;

    JobProcessor(ValidationRequestRepository requests, DocumentStorage storage,
                 DocumentValidator validator, Clock clock) {
        this.requests = requests;
        this.storage = storage;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    void process(ValidationJob job) {
        Instant now = clock.instant();

        if (requests.claimForProcessing(job.requestId(), now) == 0) {
            // Someone else has it, or this message is a redelivery of work already done.
            // Either way there is nothing to do and the message should be acked.
            log.debug("Ignoring job for {}: not claimable", job.requestId());
            return;
        }

        ValidationRequest request = requests.findById(job.requestId()).orElseThrow();

        try {
            // Inside the try, all of it. A QUEUED row whose document is missing is a
            // permanent fault, and letting it escape would redeliver forever - the exact
            // poison-message loop this catch exists to prevent.
            Document document = request.getDocument()
                    .orElseThrow(() -> new IllegalStateException("Request " + job.requestId() + " is QUEUED with no document"));
            ValidationOutcome outcome =
                    validator.validate(document.getFilename(), document.getContentType(), storage.read(document.getStorageKey()));
            if (outcome.reason() == null) {
                request.complete(outcome.fields(), now);
            } else {
                request.fail(outcome.verdict(), outcome.reason(), now);
            }
        } catch (RuntimeException e) {
            // Recorded as FAILED and acked rather than rethrown. Rethrowing a
            // non-transient fault would redeliver the same poison message forever and
            // block the partition. The cost is that a genuinely transient failure is
            // also recorded as FAILED; a retry topic and a DLQ are what fix that.
            log.error("Validation of {} failed", job.requestId(), e);
            request.fail(Verdict.ERROR, "PROCESSING_ERROR", now);
        }
    }
}

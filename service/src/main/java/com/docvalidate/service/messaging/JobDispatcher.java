package com.docvalidate.service.messaging;

import com.docvalidate.service.application.ValidationQueuedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishing happens after commit, never inside the transaction. Inside it, a job could
 * reach the broker for a write that then rolled back, and the consumer would chase a
 * requestId that does not exist. After it, an unreachable broker leaves the row QUEUED
 * with no message sent - work stranded rather than phantom, which is the better failure.
 *
 * <p>Stranded work is still a gap. The fix is a transactional outbox plus a sweeper for
 * rows left QUEUED past a threshold; both are named in the README as next work.
 */
@Component
class JobDispatcher {

    private final JobPublisher publisher;

    JobDispatcher(JobPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onQueued(ValidationQueuedEvent event) {
        publisher.publish(new ValidationJob(event.requestId()));
    }
}

package com.docvalidate.service.processing;

import com.docvalidate.service.config.DocValidateProperties;
import com.docvalidate.service.messaging.JobConsumer;
import com.docvalidate.service.messaging.ValidationJob;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class ValidationWorker implements JobConsumer {

    private final JobProcessor processor;
    private final Duration delay;

    ValidationWorker(JobProcessor processor, DocValidateProperties properties) {
        this.processor = processor;
        this.delay = properties.processingDelay();
    }

    @Override
    public void onJob(ValidationJob job) {
        if (!pause()) {
            // Interrupted mid-pause: the application is shutting down. Carrying on would
            // reach the connection pool with the interrupt flag set and fail there
            // instead. Not acking means the job is redelivered, which is the point.
            return;
        }
        processor.process(job);
    }

    /**
     * An artificial delay, outside the transaction, so the asynchronous lifecycle is
     * observable: without it the work often finishes before the SDK's first poll and
     * waitForCompletion would pass without ever having waited for anything.
     */
    private boolean pause() {
        if (delay.isZero() || delay.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

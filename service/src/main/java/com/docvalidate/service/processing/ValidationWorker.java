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
        pause();
        processor.process(job);
    }

    /**
     * An artificial delay, outside the transaction, so the asynchronous lifecycle is
     * observable: without it the work often finishes before the SDK's first poll and
     * waitForCompletion would pass without ever having waited for anything.
     */
    private void pause() {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

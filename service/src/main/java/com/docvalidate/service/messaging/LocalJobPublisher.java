package com.docvalidate.service.messaging;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The second adapter, and not only a convenience: a port with one implementation is an
 * assertion, not a demonstration. It also keeps every test except one broker-free.
 *
 * <p>Hands off to an executor rather than calling inline, so the caller returns before
 * the work runs - the same asynchrony Kafka gives, without the broker.
 */
@Component
@Profile("!kafka")
class LocalJobPublisher implements JobPublisher {

    private final JobConsumer consumer;
    private final Executor executor;

    LocalJobPublisher(JobConsumer consumer, TaskExecutor executor) {
        this.consumer = consumer;
        this.executor = executor;
    }

    @Override
    public void publish(ValidationJob job) {
        executor.execute(() -> consumer.onJob(job));
    }
}

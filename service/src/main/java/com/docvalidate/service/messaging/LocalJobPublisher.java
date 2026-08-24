package com.docvalidate.service.messaging;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The second adapter, and not only a convenience: a port with one implementation is an
 * assertion, not a demonstration. It also keeps every test except one broker-free.
 *
 * <p>Selected by {@code docvalidate.messaging=local} rather than by a profile. Keying it
 * off a profile made it the silent default of any profile that was not named "kafka", so
 * a deployment run under "prod" would have quietly lost its broker.
 *
 * <p>Hands off to an executor rather than calling inline, so the caller returns before
 * the work runs - the same asynchrony Kafka gives, without the broker.
 */
@Component
@ConditionalOnProperty(name = "docvalidate.messaging", havingValue = "local")
class LocalJobPublisher implements JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalJobPublisher.class);

    private final JobConsumer consumer;
    private final Executor executor;

    // Qualified: enabling scheduling puts a second TaskExecutor (the scheduler) in play,
    // and jobs belong on the application executor, not on the scheduling thread.
    LocalJobPublisher(JobConsumer consumer,
                      @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
                      TaskExecutor executor) {
        this.consumer = consumer;
        this.executor = executor;
        log.warn("In-memory job publisher active: jobs are not durable and do not survive restart");
    }

    @Override
    public void publish(ValidationJob job) {
        executor.execute(() -> consumer.onJob(job));
    }
}

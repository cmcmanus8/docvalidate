package com.docvalidate.service.messaging;

import com.docvalidate.service.config.DocValidateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docvalidate.messaging", havingValue = "kafka", matchIfMissing = true)
class KafkaJobPublisher implements JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobPublisher.class);

    private final KafkaTemplate<String, ValidationJob> kafka;
    private final String topic;

    KafkaJobPublisher(KafkaTemplate<String, ValidationJob> kafka, DocValidateProperties properties) {
        this.kafka = kafka;
        this.topic = properties.jobsTopic();
        log.info("Publishing validation jobs to Kafka topic {}", topic);
    }

    @Override
    public void publish(ValidationJob job) {
        try {
            // Keyed by requestId so redeliveries of one request stay on one partition and
            // therefore stay ordered relative to each other.
            kafka.send(topic, job.requestId().toString(), job)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.error("Job for {} was not published; the request stays QUEUED",
                                    job.requestId(), failure);
                        }
                    });
        } catch (RuntimeException e) {
            // send() is only asynchronous once it has metadata: with the broker down it
            // blocks for max.block.ms and then throws on the caller's thread. Left to
            // propagate it would escape the AFTER_COMMIT listener and turn a committed
            // upload into a 500, which is precisely the failure this design avoids.
            log.error("Job for {} was not published; the request stays QUEUED", job.requestId(), e);
        }
    }
}

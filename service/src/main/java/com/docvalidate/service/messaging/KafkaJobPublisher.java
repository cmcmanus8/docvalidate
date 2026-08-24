package com.docvalidate.service.messaging;

import com.docvalidate.service.config.DocValidateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka")
class KafkaJobPublisher implements JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobPublisher.class);

    private final KafkaTemplate<String, ValidationJob> kafka;
    private final String topic;

    KafkaJobPublisher(KafkaTemplate<String, ValidationJob> kafka, DocValidateProperties properties) {
        this.kafka = kafka;
        this.topic = properties.jobsTopic();
    }

    @Override
    public void publish(ValidationJob job) {
        // Keyed by requestId so redeliveries of one request stay on one partition and
        // therefore stay ordered relative to each other.
        kafka.send(topic, job.requestId().toString(), job)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error("Job for {} was not published; the request stays QUEUED", job.requestId(), failure);
                    }
                });
    }
}

package com.docvalidate.service.messaging;

import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka")
class KafkaJobListener {

    private final JobConsumer consumer;

    KafkaJobListener(JobConsumer consumer) {
        this.consumer = consumer;
    }

    @KafkaListener(topics = "${docvalidate.jobs-topic}", groupId = "${spring.kafka.consumer.group-id}")
    void onMessage(ValidationJob job) {
        consumer.onJob(job);
    }
}

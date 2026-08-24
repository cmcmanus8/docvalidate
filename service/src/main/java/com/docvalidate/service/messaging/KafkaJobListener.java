package com.docvalidate.service.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docvalidate.messaging", havingValue = "kafka", matchIfMissing = true)
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

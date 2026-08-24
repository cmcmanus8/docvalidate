package com.docvalidate.service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "docvalidate.messaging", havingValue = "kafka", matchIfMissing = true)
class KafkaTopicConfig {

    /**
     * Created on startup so a fresh checkout needs no broker admin step. Three partitions
     * because jobs are keyed by requestId: parallel consumers, ordering kept per request.
     */
    @Bean
    NewTopic validationJobs(DocValidateProperties properties) {
        return TopicBuilder.name(properties.jobsTopic()).partitions(3).replicas(1).build();
    }
}

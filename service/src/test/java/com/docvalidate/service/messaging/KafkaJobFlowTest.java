package com.docvalidate.service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docvalidate.service.application.ValidationService;
import com.docvalidate.service.domain.ValidationStatus;
import com.docvalidate.service.persistence.ValidationRequestRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The only test that pays for a broker. Everything else runs against the in-memory
 * adapter, which is the point of the port: this one proves the Kafka wiring, the others
 * prove the behaviour without waiting 20 seconds for a container.
 */
@Testcontainers
@ActiveProfiles("kafka")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "docvalidate.storage-root=build/test-storage",
        "docvalidate.processing-delay=PT0S"
})
class KafkaJobFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    ValidationService validations;

    @Autowired
    ValidationRequestRepository requests;

    @Test
    void aJobTravelsThroughKafkaAndTheRequestReachesCompleted() {
        UUID requestId = validations.create(null).request().getId();
        validations.upload(requestId, "march-invoice.pdf", "application/pdf", "line one\n".getBytes());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(requests.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(ValidationStatus.COMPLETED));
    }
}

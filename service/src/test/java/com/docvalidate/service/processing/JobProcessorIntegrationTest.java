package com.docvalidate.service.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.docvalidate.service.application.ValidationService;
import com.docvalidate.service.domain.ValidationStatus;
import com.docvalidate.service.domain.Verdict;
import com.docvalidate.service.messaging.ValidationJob;
import com.docvalidate.service.persistence.PostgresTestBase;
import com.docvalidate.service.persistence.ValidationRequestRepository;
import com.docvalidate.service.persistence.ValidationResultRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "docvalidate.storage-root=build/test-storage",
        "docvalidate.processing-delay=PT0S"
})
class JobProcessorIntegrationTest extends PostgresTestBase {

    @Autowired
    ValidationService validations;

    @Autowired
    JobProcessor processor;

    @Autowired
    ValidationRequestRepository requests;

    @Autowired
    ValidationResultRepository results;

    @Test
    void anUploadedDocumentIsProcessedWithoutTheCallerWaitingForIt() {
        UUID requestId = upload("march-invoice.pdf", "application/pdf", "line one\nline two\n");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(requests.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(ValidationStatus.COMPLETED));

        assertThat(results.findByRequestId(requestId)).get().satisfies(result -> {
            assertThat(result.getVerdict()).isEqualTo(Verdict.VALID);
            assertThat(result.getExtractedFields()).containsEntry("documentType", "INVOICE");
        });
    }

    @Test
    void aDocumentTheValidatorRejectsIsRecordedAsFailed() {
        UUID requestId = upload("archive.zip", "application/zip", "not a document");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(requests.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(ValidationStatus.FAILED));

        assertThat(results.findByRequestId(requestId)).get()
                .satisfies(result -> assertThat(result.getReason()).isEqualTo("UNSUPPORTED_CONTENT_TYPE"));
    }

    /**
     * The assumption the whole asynchronous design rests on: Kafka delivers at least
     * once, so the second delivery of a job has to be a no-op rather than a second run.
     */
    @Test
    void deliveringTheSameJobTwiceProducesExactlyOneResult() {
        UUID requestId = upload("receipt.txt", "text/plain", "a receipt");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(requests.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(ValidationStatus.COMPLETED));

        processor.process(new ValidationJob(requestId));

        assertThat(results.countByRequestId(requestId)).isEqualTo(1);
        assertThat(requests.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ValidationStatus.COMPLETED);
    }

    private UUID upload(String filename, String contentType, String body) {
        UUID requestId = validations.create(null).request().getId();
        validations.upload(requestId, filename, contentType, body.getBytes());
        return requestId;
    }
}

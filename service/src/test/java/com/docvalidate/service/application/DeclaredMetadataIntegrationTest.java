package com.docvalidate.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docvalidate.service.domain.ValidationStatus;
import com.docvalidate.service.persistence.PostgresTestBase;
import com.docvalidate.service.persistence.ValidationRequestRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Declaring the document at create time is optional, so both halves need proving: the
 * upload can lean on the declaration, and it cannot contradict it.
 */
@TestPropertySource(properties = "docvalidate.storage-root=build/test-storage")
class DeclaredMetadataIntegrationTest extends PostgresTestBase {

    @Autowired
    ValidationService validations;

    @Autowired
    ValidationRequestRepository requests;

    @Test
    void anUploadFallsBackToTheFilenameDeclaredAtCreateTime() {
        UUID requestId = validations.create(null, "declared-invoice.pdf", "application/pdf").request().getId();

        validations.upload(requestId, null, "application/pdf", "hello".getBytes());

        assertThat(requests.findById(requestId).orElseThrow().getDocument())
                .get()
                .satisfies(document -> assertThat(document.getFilename()).isEqualTo("declared-invoice.pdf"));
    }

    @Test
    void anUploadWithNoFilenameAndNoDeclarationIsRejected() {
        UUID requestId = validations.create(null).request().getId();

        assertThatThrownBy(() -> validations.upload(requestId, null, "application/pdf", "hello".getBytes()))
                .isInstanceOf(MissingFilenameException.class);
    }

    @Test
    void bytesThatContradictTheDeclaredTypeAreRefused() {
        UUID requestId = validations.create(null, "invoice.pdf", "application/pdf").request().getId();

        assertThatThrownBy(() -> validations.upload(requestId, "invoice.png", "image/png", "hello".getBytes()))
                .isInstanceOf(DeclaredTypeMismatchException.class);

        assertThat(requests.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ValidationStatus.PENDING_UPLOAD);
    }

    /**
     * The retry window used to close the moment the worker claimed the job, roughly a second
     * after upload, which made "identical bytes are safe to resend" true only in theory.
     */
    @Test
    void identicalBytesAreAReplayInEveryStatus() {
        UUID requestId = validations.create(null, "invoice.pdf", "application/pdf").request().getId();
        byte[] content = "invoice total: 42.00".getBytes();
        validations.upload(requestId, null, "application/pdf", content);

        requests.claimForProcessing(requestId, java.time.Instant.now());
        assertThat(requests.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ValidationStatus.PROCESSING);

        assertThat(validations.upload(requestId, null, "application/pdf", content))
                .isEqualTo(UploadOutcome.ALREADY_ACCEPTED);
    }

    @Test
    void differentBytesAreRefusedInEveryStatus() {
        UUID requestId = validations.create(null, "invoice.pdf", "application/pdf").request().getId();
        validations.upload(requestId, null, "application/pdf", "first".getBytes());
        requests.claimForProcessing(requestId, java.time.Instant.now());

        assertThatThrownBy(() -> validations.upload(requestId, null, "application/pdf", "second".getBytes()))
                .isInstanceOf(ContentMismatchException.class);
    }

    @Test
    void anUploadToAnUnknownRequestIs404EvenWhenItIsTooLarge() {
        assertThatThrownBy(() -> validations.upload(UUID.randomUUID(), "a.pdf", "application/pdf", new byte[0]))
                .isInstanceOf(ValidationNotFoundException.class);
    }
}

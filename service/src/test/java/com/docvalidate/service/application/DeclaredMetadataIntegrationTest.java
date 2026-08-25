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
}

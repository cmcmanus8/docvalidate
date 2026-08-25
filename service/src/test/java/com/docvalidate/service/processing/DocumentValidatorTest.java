package com.docvalidate.service.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.docvalidate.service.config.DocValidateProperties;
import com.docvalidate.service.domain.Verdict;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class DocumentValidatorTest {

    private final DocumentValidator validator = new DocumentValidator(
            new DocValidateProperties(Duration.ofMinutes(15), DataSize.ofMegabytes(10), Path.of("./.data"),
                    "jobs", Duration.ZERO, Set.of("application/pdf", "text/plain")));

    @Test
    void anEmptyDocumentFails() {
        ValidationOutcome outcome = validator.validate("invoice.pdf", "application/pdf", new byte[0]);

        assertThat(outcome.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(outcome.reason()).isEqualTo("EMPTY_DOCUMENT");
    }

    @Test
    void aContentTypeOutsideTheAllowedSetFails() {
        ValidationOutcome outcome = validator.validate("virus.exe", "application/x-msdownload", "MZ".getBytes());

        assertThat(outcome.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(outcome.reason()).isEqualTo("UNSUPPORTED_CONTENT_TYPE");
    }

    @Test
    void aValidDocumentCarriesExtractedFields() {
        ValidationOutcome outcome = validator.validate("march-invoice.pdf", "application/pdf", "one\ntwo\n".getBytes());

        assertThat(outcome.verdict()).isEqualTo(Verdict.PASS);
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.fields())
                .containsEntry("documentType", "INVOICE")
                .containsEntry("sizeBytes", 8)
                .containsEntry("lineCount", 2L);
    }

    @Test
    void isDeterministic() {
        byte[] content = "receipt body".getBytes();

        assertThat(validator.validate("receipt.txt", "text/plain", content))
                .isEqualTo(validator.validate("receipt.txt", "text/plain", content));
    }
}

package com.docvalidate.service.api;

import com.docvalidate.service.domain.Document;
import com.docvalidate.service.domain.ValidationRequest;
import com.docvalidate.service.domain.ValidationResult;
import com.docvalidate.service.domain.ValidationStatus;
import com.docvalidate.service.domain.Verdict;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationView(UUID requestId, ValidationStatus status, Instant createdAt, Instant updatedAt,
                             Instant expiresAt, DocumentView document, ResultView result) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentView(String filename, String contentType, long sizeBytes, String sha256) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResultView(Verdict verdict, String reason, Map<String, Object> fields) {
    }

    public static ValidationView of(ValidationRequest request) {
        return new ValidationView(
                request.getId(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                request.getExpiresAt(),
                request.getDocument().map(ValidationView::documentView).orElse(null),
                request.getResult().map(ValidationView::resultView).orElse(null));
    }

    private static DocumentView documentView(Document document) {
        return new DocumentView(document.getFilename(), document.getContentType(),
                document.getSizeBytes(), document.getSha256());
    }

    private static ResultView resultView(ValidationResult result) {
        return new ResultView(result.getVerdict(), result.getReason(), result.getFields());
    }
}

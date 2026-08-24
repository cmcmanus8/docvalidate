package com.docvalidate.service.processing;

import com.docvalidate.service.domain.Verdict;
import java.util.Map;

public record ValidationOutcome(Verdict verdict, String reason, Map<String, Object> extractedFields) {

    static ValidationOutcome valid(Map<String, Object> extractedFields) {
        return new ValidationOutcome(Verdict.VALID, null, extractedFields);
    }

    static ValidationOutcome invalid(String reason) {
        return new ValidationOutcome(Verdict.INVALID, reason, Map.of());
    }
}

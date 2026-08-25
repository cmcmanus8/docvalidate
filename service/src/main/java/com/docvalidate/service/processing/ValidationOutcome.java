package com.docvalidate.service.processing;

import com.docvalidate.service.domain.Verdict;
import java.util.Map;

public record ValidationOutcome(Verdict verdict, String reason, Map<String, Object> fields) {

    static ValidationOutcome pass(Map<String, Object> fields) {
        return new ValidationOutcome(Verdict.PASS, null, fields);
    }

    static ValidationOutcome fail(String reason) {
        return new ValidationOutcome(Verdict.FAIL, reason, Map.of());
    }
}

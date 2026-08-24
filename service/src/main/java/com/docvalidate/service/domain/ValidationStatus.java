package com.docvalidate.service.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum ValidationStatus {

    PENDING_UPLOAD,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED;

    private static final Map<ValidationStatus, Set<ValidationStatus>> ALLOWED = Map.of(
            PENDING_UPLOAD, EnumSet.of(QUEUED, EXPIRED),
            QUEUED, EnumSet.of(PROCESSING),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(ValidationStatus.class),
            FAILED, EnumSet.noneOf(ValidationStatus.class),
            EXPIRED, EnumSet.noneOf(ValidationStatus.class));

    public boolean canTransitionTo(ValidationStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}

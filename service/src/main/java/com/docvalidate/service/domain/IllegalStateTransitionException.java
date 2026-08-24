package com.docvalidate.service.domain;

import java.util.UUID;

public class IllegalStateTransitionException extends RuntimeException {

    private final UUID requestId;
    private final ValidationStatus current;
    private final ValidationStatus attempted;

    public IllegalStateTransitionException(UUID requestId, ValidationStatus current, ValidationStatus attempted) {
        super("Request %s is in status %s and cannot move to %s".formatted(requestId, current, attempted));
        this.requestId = requestId;
        this.current = current;
        this.attempted = attempted;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public ValidationStatus getCurrent() {
        return current;
    }

    public ValidationStatus getAttempted() {
        return attempted;
    }
}

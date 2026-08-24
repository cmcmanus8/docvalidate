package com.docvalidate.service.application;

import java.util.UUID;

public class ValidationNotFoundException extends RuntimeException {

    private final UUID requestId;

    public ValidationNotFoundException(UUID requestId) {
        super("No validation request with id " + requestId);
        this.requestId = requestId;
    }

    public UUID getRequestId() {
        return requestId;
    }
}

package com.docvalidate.service.application;

import java.util.UUID;

/** The bytes are not the kind of document the request said it would carry. */
public class DeclaredTypeMismatchException extends RuntimeException {

    private final UUID requestId;

    public DeclaredTypeMismatchException(UUID requestId, String declared, String actual) {
        super("Request %s declared %s but the upload is %s".formatted(requestId, declared, actual));
        this.requestId = requestId;
    }

    public UUID getRequestId() {
        return requestId;
    }
}

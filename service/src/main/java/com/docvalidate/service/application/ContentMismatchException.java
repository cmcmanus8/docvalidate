package com.docvalidate.service.application;

import java.util.UUID;

/** A second upload carrying different bytes to a request that already has content. */
public class ContentMismatchException extends RuntimeException {

    private final UUID requestId;

    public ContentMismatchException(UUID requestId) {
        super("Request %s already holds different content; documents are immutable once accepted".formatted(requestId));
        this.requestId = requestId;
    }

    public UUID getRequestId() {
        return requestId;
    }
}

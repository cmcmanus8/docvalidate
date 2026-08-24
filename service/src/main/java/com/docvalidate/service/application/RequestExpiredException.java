package com.docvalidate.service.application;

import java.util.UUID;

public class RequestExpiredException extends RuntimeException {

    private final UUID requestId;

    public RequestExpiredException(UUID requestId) {
        super("The upload window for request " + requestId + " has closed");
        this.requestId = requestId;
    }

    public UUID getRequestId() {
        return requestId;
    }
}

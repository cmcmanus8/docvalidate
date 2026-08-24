package com.docvalidate.service.application;

public class PayloadTooLargeException extends RuntimeException {

    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        super("Upload exceeds the " + limitBytes + " byte limit");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }
}

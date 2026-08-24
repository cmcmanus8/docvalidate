package com.docvalidate.service.api;

public class InvalidContentTypeException extends RuntimeException {

    public InvalidContentTypeException(String contentType) {
        super("Content-Type is not a valid media type: " + contentType);
    }
}

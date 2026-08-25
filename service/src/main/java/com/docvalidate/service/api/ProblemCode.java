package com.docvalidate.service.api;

/**
 * Stable machine-readable codes carried alongside the RFC 9457 fields. The SDK
 * branches on these rather than on HTTP status or on prose, so they are contract:
 * renaming one is a breaking change.
 */
public enum ProblemCode {

    VALIDATION_NOT_FOUND,
    INVALID_STATE_TRANSITION,
    CONTENT_MISMATCH,
    DECLARED_TYPE_MISMATCH,
    REQUEST_EXPIRED,
    PAYLOAD_TOO_LARGE,
    LENGTH_REQUIRED,
    INVALID_REQUEST,
    RESOURCE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    STORAGE_FAILURE,
    INTERNAL_ERROR;

    public String type() {
        return "https://docvalidate.dev/problems/" + name().toLowerCase().replace('_', '-');
    }
}

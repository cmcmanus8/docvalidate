package com.docvalidate.service.application;

public enum UploadOutcome {

    /** Bytes accepted, request moved to QUEUED. */
    ACCEPTED,

    /** A replay of an upload we already hold, byte for byte. No state change. */
    ALREADY_ACCEPTED
}

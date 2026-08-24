package com.docvalidate.service.application;

import java.util.UUID;

/** Raised once the bytes are stored and the aggregate has moved to QUEUED. */
public record ValidationQueuedEvent(UUID requestId) {
}

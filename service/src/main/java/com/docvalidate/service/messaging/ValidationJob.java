package com.docvalidate.service.messaging;

import java.util.UUID;

/**
 * Carries the id and nothing else. The consumer reads current state from the database
 * rather than trusting a snapshot that was true when the message was written.
 */
public record ValidationJob(UUID requestId) {
}

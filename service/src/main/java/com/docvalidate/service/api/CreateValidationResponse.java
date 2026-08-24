package com.docvalidate.service.api;

import com.docvalidate.service.domain.ValidationStatus;
import java.time.Instant;
import java.util.UUID;

public record CreateValidationResponse(UUID requestId, String uploadUrl, ValidationStatus status, Instant expiresAt) {
}

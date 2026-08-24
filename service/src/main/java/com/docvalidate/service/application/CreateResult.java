package com.docvalidate.service.application;

import com.docvalidate.service.domain.ValidationRequest;

/**
 * @param replayed true when an Idempotency-Key matched an existing request, which the
 *                 controller answers with 200 rather than 201 — nothing was created.
 */
public record CreateResult(ValidationRequest request, boolean replayed) {
}

package com.docvalidate.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationRequestTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Test
    void isCreatedPendingUploadWithAnExpiryWindow() {
        ValidationRequest request = ValidationRequest.create("key-1", NOW, WINDOW);

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.PENDING_UPLOAD);
        assertThat(request.getExpiresAt()).isEqualTo(NOW.plus(WINDOW));
        assertThat(request.getDocument()).isEmpty();
        assertThat(request.getResult()).isEmpty();
    }

    @Test
    void queuesOnUploadAndCompletesAfterProcessing() {
        ValidationRequest request = ValidationRequest.create(null, NOW, WINDOW);

        request.attachDocument("invoice.pdf", "application/pdf", 128L, "a".repeat(64), "key", NOW);
        assertThat(request.getStatus()).isEqualTo(ValidationStatus.QUEUED);
        assertThat(request.getDocument()).isPresent();

        request.transitionTo(ValidationStatus.PROCESSING, NOW);
        request.complete(Map.of("pages", 2), NOW);

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(request.getResult()).get().extracting(ValidationResult::getVerdict).isEqualTo(Verdict.VALID);
    }

    @Test
    void rejectsAnUploadOnceProcessingHasStarted() {
        ValidationRequest request = ValidationRequest.create(null, NOW, WINDOW);
        request.attachDocument("invoice.pdf", "application/pdf", 128L, "a".repeat(64), "key", NOW);
        request.transitionTo(ValidationStatus.PROCESSING, NOW);

        assertThatThrownBy(() -> request.attachDocument("other.pdf", "application/pdf", 1L, "b".repeat(64), "key2", NOW))
                .isInstanceOf(IllegalStateTransitionException.class)
                .satisfies(e -> {
                    IllegalStateTransitionException ex = (IllegalStateTransitionException) e;
                    assertThat(ex.getCurrent()).isEqualTo(ValidationStatus.PROCESSING);
                    assertThat(ex.getAttempted()).isEqualTo(ValidationStatus.QUEUED);
                });
    }

    @Test
    void terminalStatusesAcceptNoFurtherTransitions() {
        ValidationRequest request = ValidationRequest.create(null, NOW, WINDOW);
        request.expire(NOW.plus(WINDOW));

        assertThat(request.getStatus()).isEqualTo(ValidationStatus.EXPIRED);
        assertThat(request.getStatus().isTerminal()).isTrue();
        assertThatThrownBy(() -> request.transitionTo(ValidationStatus.QUEUED, NOW))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void isOnlyExpiredWhileStillAwaitingAnUpload() {
        ValidationRequest request = ValidationRequest.create(null, NOW, WINDOW);
        assertThat(request.isExpiredAt(NOW.plus(WINDOW).minusSeconds(1))).isFalse();
        assertThat(request.isExpiredAt(NOW.plus(WINDOW))).isTrue();

        request.attachDocument("invoice.pdf", "application/pdf", 1L, "a".repeat(64), "key", NOW);
        assertThat(request.isExpiredAt(NOW.plus(WINDOW))).isFalse();
    }
}

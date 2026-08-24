package com.docvalidate.service.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.docvalidate.service.domain.ValidationRequest;
import com.docvalidate.service.domain.ValidationStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ValidationRequestPersistenceTest extends PostgresTestBase {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Autowired
    ValidationRequestRepository requests;

    @Autowired
    EntityManager entityManager;

    @Test
    void migrationsApplyAndTheAggregateRoundTrips() {
        Instant now = Instant.now();
        ValidationRequest saved = requests.save(ValidationRequest.create("key-round-trip", now, WINDOW));

        saved.attachDocument("invoice.pdf", "application/pdf", 42L, "a".repeat(64), "key/invoice.pdf", now);
        requests.saveAndFlush(saved);
        entityManager.clear();

        ValidationRequest loaded = requests.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ValidationStatus.QUEUED);
        assertThat(loaded.getIdempotencyKey()).isEqualTo("key-round-trip");
        assertThat(loaded.getDocument()).get().satisfies(document -> {
            assertThat(document.getFilename()).isEqualTo("invoice.pdf");
            assertThat(document.getSizeBytes()).isEqualTo(42L);
        });
    }

    @Test
    void findsAPriorRequestByItsIdempotencyKey() {
        Instant now = Instant.now();
        ValidationRequest saved = requests.saveAndFlush(ValidationRequest.create("key-replay", now, WINDOW));

        assertThat(requests.findByIdempotencyKey("key-replay"))
                .get()
                .extracting(ValidationRequest::getId)
                .isEqualTo(saved.getId());
        assertThat(requests.findByIdempotencyKey("never-issued")).isEmpty();
    }

    @Test
    void onlyTheFirstClaimOfAQueuedRequestSucceeds() {
        Instant now = Instant.now();
        ValidationRequest request = ValidationRequest.create(null, now, WINDOW);
        request.attachDocument("invoice.pdf", "application/pdf", 1L, "a".repeat(64), "key", now);
        requests.saveAndFlush(request);

        assertThat(requests.claimForProcessing(request.getId(), now)).isEqualTo(1);
        assertThat(requests.claimForProcessing(request.getId(), now)).isZero();

        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ValidationStatus.PROCESSING);
    }

    @Test
    void claimingAnUnknownRequestAffectsNothing() {
        assertThat(requests.claimForProcessing(UUID.randomUUID(), Instant.now())).isZero();
    }
}

package com.docvalidate.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docvalidate.service.domain.IllegalStateTransitionException;
import com.docvalidate.service.domain.ValidationRequest;
import com.docvalidate.service.domain.ValidationStatus;
import com.docvalidate.service.persistence.PostgresTestBase;
import com.docvalidate.service.persistence.ValidationRequestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/**
 * The rules the README promises, against a real database: an Idempotency-Key never
 * mints two requests, an expired window is actually recorded, and a rejected upload
 * leaves the filesystem alone.
 */
@TestPropertySource(properties = {
        "docvalidate.upload-window=PT0S",
        "docvalidate.storage-root=build/test-storage"
})
class ValidationServiceIntegrationTest extends PostgresTestBase {

    @Autowired
    ValidationService validations;

    @Autowired
    ValidationRequestRepository requests;

    @Value("${docvalidate.storage-root}")
    Path storageRoot;

    @Test
    void concurrentCreatesWithTheSameKeyYieldOneRequest() throws Exception {
        String key = "race-" + UUID.randomUUID();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<CreateResult> create = () -> {
            startTogether.await();
            return validations.create(key);
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<CreateResult>> results = pool.invokeAll(List.of(create, create));
            UUID first = results.get(0).get().request().getId();
            UUID second = results.get(1).get().request().getId();

            assertThat(first).isEqualTo(second);
        }

        assertThat(requests.findByIdempotencyKey(key)).isPresent();
    }

    @Test
    void anUploadAfterTheWindowClosesCommitsTheExpiredTransition() {
        UUID requestId = validations.create(null).request().getId();

        UploadOutcome outcome = validations.upload(requestId, "invoice.pdf", "application/pdf", "hello".getBytes());

        assertThat(outcome).isEqualTo(UploadOutcome.EXPIRED);
        // Re-read rather than trusting the in-memory aggregate: the point of the test is
        // that the transition survived the transaction that reported it.
        assertThat(requests.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ValidationStatus.EXPIRED);
    }

    @Test
    void aRejectedUploadNeverTouchesStorage() {
        UUID requestId = seedProcessingRequest();

        assertThatThrownBy(() -> validations.upload(requestId, "invoice.pdf", "application/pdf", "hello".getBytes()))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(Files.exists(storageRoot.resolve(requestId.toString()))).isFalse();
    }

    private UUID seedProcessingRequest() {
        UUID requestId = validations.create(null).request().getId();
        ValidationRequest request = requests.findById(requestId).orElseThrow();
        request.attachDocument("invoice.pdf", "application/pdf", 5L, "a".repeat(64), "seeded", Instant.now());
        requests.saveAndFlush(request);
        requests.claimForProcessing(requestId, Instant.now());
        return requestId;
    }
}

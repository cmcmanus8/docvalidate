package com.docvalidate.service.persistence;

import com.docvalidate.service.domain.ValidationRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ValidationRequestRepository extends JpaRepository<ValidationRequest, UUID> {

    Optional<ValidationRequest> findByIdempotencyKey(String idempotencyKey);

    /**
     * Conditional claim: only the caller that sees the row in QUEUED wins. Zero rows
     * affected means another consumer already has it, or this is a redelivery of a
     * message we have already handled. Deliberately a single statement rather than a
     * read-then-write, which would race under at-least-once delivery.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE validation.validation_request
               SET status = 'PROCESSING', updated_at = :now, version = version + 1
             WHERE id = :id AND status = 'QUEUED'
            """, nativeQuery = true)
    int claimForProcessing(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Closes the upload window on requests nobody came back for. Set-based rather than
     * loaded-and-looped: there is no per-row decision to make, and the index on status
     * exists for exactly this query.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE validation.validation_request
               SET status = 'EXPIRED', updated_at = :now, version = version + 1
             WHERE status = 'PENDING_UPLOAD' AND expires_at < :now
            """, nativeQuery = true)
    int expireAbandoned(@Param("now") Instant now);
}

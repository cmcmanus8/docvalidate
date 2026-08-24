package com.docvalidate.service.persistence;

import com.docvalidate.service.domain.ValidationResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationResultRepository extends JpaRepository<ValidationResult, UUID> {

    Optional<ValidationResult> findByRequestId(UUID requestId);

    long countByRequestId(UUID requestId);
}

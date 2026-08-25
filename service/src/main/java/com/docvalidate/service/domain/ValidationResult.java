package com.docvalidate.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "validation_result")
public class ValidationResult {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private ValidationRequest request;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 32)
    private Verdict verdict;

    /** Machine-readable failure code, e.g. EMPTY_DOCUMENT. Null on a successful verdict. */
    @Column(name = "reason")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fields", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> fields;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ValidationResult() {
    }

    public ValidationResult(ValidationRequest request, Verdict verdict, String reason,
                           Map<String, Object> fields, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.request = request;
        this.verdict = verdict;
        this.reason = reason;
        this.fields = fields == null ? Map.of() : fields;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public ValidationRequest getRequest() {
        return request;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

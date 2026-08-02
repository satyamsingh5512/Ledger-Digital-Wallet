package com.walletsys.entity;

import com.walletsys.entity.enums.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Deduplication record for client-submitted mutating requests (transfers, credits,
 * debits, refunds) sent over an at-least-once transport.
 *
 * <p>Flow: on request, the service attempts to insert a row with status
 * {@code IN_PROGRESS} guarded by the unique constraint on {@code idempotencyKey}.
 * <ul>
 *   <li>If the insert succeeds, this is the first attempt — proceed and later update
 *       the row with the final response.</li>
 *   <li>If it fails with a unique-violation, an existing row is fetched. If its
 *       {@code requestHash} matches the current request, the stored response is
 *       replayed verbatim (idempotent retry). If it differs, the client is reusing a key
 *       for a different payload, which is a 409/422 client error, not a replay.</li>
 * </ul>
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode(callSuper = true)
public class IdempotencyKey extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    /** SHA-256 hex digest of the canonicalized request body. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private String endpoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private IdempotencyStatus status = IdempotencyStatus.IN_PROGRESS;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}

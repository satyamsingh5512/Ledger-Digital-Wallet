package com.walletsys.entity;

import com.walletsys.entity.enums.AggregateType;
import com.walletsys.entity.enums.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.UUID;

/**
 * Transactional outbox row: written in the exact same DB transaction as the business
 * change it describes, then asynchronously polled and published to Kafka.
 *
 * <p>This is what gives us atomicity between "the wallet balance changed" and "an event
 * was published about it" without a distributed transaction / 2PC across Postgres and
 * Kafka. If the process crashes after the DB commit but before publishing, the row is
 * still there and the poller picks it up on the next cycle — at-least-once delivery.
 * Consumers (including our own notification consumer) must therefore be idempotent,
 * keyed on {@code id} (used as the Kafka message key / dedup token).</p>
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode(callSuper = true)
public class OutboxEvent extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private AggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "published_at")
    private Instant publishedAt;
}

package com.walletsys.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when a refund completes successfully. */
public record RefundCompletedEvent(
        UUID eventId,
        UUID refundId,
        UUID originalTransactionId,
        UUID refundTransactionId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
}

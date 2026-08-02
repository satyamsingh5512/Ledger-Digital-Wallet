package com.walletsys.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when a wallet is credited (top-up, incoming transfer leg, refund leg). */
public record MoneyCreditedEvent(
        UUID eventId,
        UUID transactionId,
        UUID walletId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String currency,
        Instant occurredAt
) {
}

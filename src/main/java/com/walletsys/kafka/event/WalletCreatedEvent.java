package com.walletsys.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when a new wallet is created. */
public record WalletCreatedEvent(
        UUID eventId,
        UUID walletId,
        UUID userId,
        String currency,
        BigDecimal initialBalance,
        Instant occurredAt
) {
}

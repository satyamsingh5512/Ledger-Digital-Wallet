package com.walletsys.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when a wallet is debited (withdrawal, outgoing transfer leg). */
public record MoneyDebitedEvent(
        UUID eventId,
        UUID transactionId,
        UUID walletId,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String currency,
        Instant occurredAt
) {
}

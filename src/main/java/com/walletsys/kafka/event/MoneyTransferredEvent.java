package com.walletsys.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when a transfer between two wallets completes successfully. */
public record MoneyTransferredEvent(
        UUID eventId,
        UUID transactionId,
        String referenceId,
        UUID sourceWalletId,
        UUID destinationWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
}

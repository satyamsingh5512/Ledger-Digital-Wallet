package com.walletsys.entity.enums;

/**
 * Workflow status of a {@link com.walletsys.entity.Transaction}.
 * Unlike ledger entries, transactions are mutable workflow records:
 * PENDING -&gt; COMPLETED | FAILED, and COMPLETED -&gt; REVERSED (via refund).
 */
public enum TransactionStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REVERSED
}

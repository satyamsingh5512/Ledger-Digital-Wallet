package com.walletsys.entity.enums;

/** Aggregate root type that an {@link com.walletsys.entity.OutboxEvent} originates from. */
public enum AggregateType {
    WALLET,
    TRANSACTION,
    REFUND
}

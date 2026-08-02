package com.walletsys.entity.enums;

/** Processing state of an {@link com.walletsys.entity.IdempotencyKey} record. */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

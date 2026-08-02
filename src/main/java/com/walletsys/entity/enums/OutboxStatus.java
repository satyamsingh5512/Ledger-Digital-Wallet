package com.walletsys.entity.enums;

/** Delivery status of an {@link com.walletsys.entity.OutboxEvent}. */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}

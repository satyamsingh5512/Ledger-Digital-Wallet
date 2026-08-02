package com.walletsys.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a client reuses an Idempotency-Key with a request body whose hash does
 * not match the original request stored for that key. This is a client error (they are
 * either buggy or attempting a replay attack with different parameters), not a retry.
 */
public class IdempotencyKeyConflictException extends WalletSysException {

    public IdempotencyKeyConflictException(String message) {
        super("IDEMPOTENCY_KEY_CONFLICT", HttpStatus.CONFLICT, message);
    }
}

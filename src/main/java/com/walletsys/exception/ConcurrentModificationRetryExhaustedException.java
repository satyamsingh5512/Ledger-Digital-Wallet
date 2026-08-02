package com.walletsys.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised after exhausting all optimistic-lock retry attempts on a wallet balance
 * mutation under sustained contention. Distinct from a business-rule violation — the
 * client should retry the whole request (idempotency key makes this safe) after a short
 * backoff.
 */
public class ConcurrentModificationRetryExhaustedException extends WalletSysException {

    public ConcurrentModificationRetryExhaustedException(String message) {
        super("CONCURRENT_MODIFICATION_RETRY_EXHAUSTED", HttpStatus.CONFLICT, message);
    }
}

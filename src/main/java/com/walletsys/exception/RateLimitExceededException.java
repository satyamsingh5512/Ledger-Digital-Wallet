package com.walletsys.exception;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends WalletSysException {

    public RateLimitExceededException(String message) {
        super("RATE_LIMIT_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS, message);
    }
}

package com.walletsys.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a JWT is expired, malformed, or fails signature verification. */
public class InvalidTokenException extends WalletSysException {

    public InvalidTokenException(String message) {
        super("INVALID_TOKEN", HttpStatus.UNAUTHORIZED, message);
    }
}

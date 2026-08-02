package com.walletsys.exception;

import org.springframework.http.HttpStatus;

/** Base for all application-specific exceptions carrying a stable error code + HTTP status. */
public abstract class WalletSysException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    protected WalletSysException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

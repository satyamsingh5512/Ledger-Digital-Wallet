package com.walletsys.exception;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends WalletSysException {

    public InvalidCredentialsException(String message) {
        super("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, message);
    }
}

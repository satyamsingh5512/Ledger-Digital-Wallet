package com.walletsys.exception;

import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends WalletSysException {

    public InsufficientBalanceException(String message) {
        super("INSUFFICIENT_BALANCE", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

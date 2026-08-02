package com.walletsys.exception;

import org.springframework.http.HttpStatus;

public class RefundNotAllowedException extends WalletSysException {

    public RefundNotAllowedException(String message) {
        super("REFUND_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

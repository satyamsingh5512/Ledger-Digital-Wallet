package com.walletsys.exception;

import org.springframework.http.HttpStatus;

/** Thrown when a wallet is FROZEN/CLOSED and cannot participate in a mutating operation. */
public class WalletNotActiveException extends WalletSysException {

    public WalletNotActiveException(String message) {
        super("WALLET_NOT_ACTIVE", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}

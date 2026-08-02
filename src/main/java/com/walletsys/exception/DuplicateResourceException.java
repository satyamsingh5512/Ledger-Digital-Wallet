package com.walletsys.exception;

import org.springframework.http.HttpStatus;

/** Thrown on registration with a duplicate email, duplicate reference id, etc. */
public class DuplicateResourceException extends WalletSysException {

    public DuplicateResourceException(String message) {
        super("DUPLICATE_RESOURCE", HttpStatus.CONFLICT, message);
    }
}

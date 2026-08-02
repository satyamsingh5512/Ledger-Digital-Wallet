package com.walletsys.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends WalletSysException {

    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }
}

package com.dastakhat.common;

import org.springframework.http.HttpStatus;

/** 404 - the resource does not exist or is not visible to the caller. */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND.value(), "not_found", message);
    }
}

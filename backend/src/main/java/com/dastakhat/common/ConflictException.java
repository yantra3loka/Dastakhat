package com.dastakhat.common;

import org.springframework.http.HttpStatus;

/** 409 - the request conflicts with the current state of the resource. */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT.value(), "conflict", message);
    }
}

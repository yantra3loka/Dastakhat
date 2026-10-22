package com.dastakhat.common;

import org.springframework.http.HttpStatus;

/** 422 - the payload is syntactically fine but semantically rejected. */
public class UnprocessableException extends ApiException {
    public UnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY.value(), "unprocessable", message);
    }
}

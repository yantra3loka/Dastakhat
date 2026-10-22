package com.dastakhat.common;

import org.springframework.http.HttpStatus;

/** 413 - the uploaded payload is too large. */
public class PayloadTooLargeException extends ApiException {
    public PayloadTooLargeException(String message) {
        super(HttpStatus.PAYLOAD_TOO_LARGE.value(), "payload_too_large", message);
    }
}

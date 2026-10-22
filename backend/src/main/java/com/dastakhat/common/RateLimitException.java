package com.dastakhat.common;

import org.springframework.http.HttpStatus;

/** 429 - the caller exceeded the configured rate limit. */
public class RateLimitException extends ApiException {
    public RateLimitException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS.value(), "too_many_requests", message);
    }
}

package com.dastakhat.common;

import org.springframework.http.HttpStatus;

/** 415 - the payload is not of a supported media type (e.g. not a PDF). */
public class UnsupportedMediaTypeException extends ApiException {
    public UnsupportedMediaTypeException(String message) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "unsupported_media_type", message);
    }
}

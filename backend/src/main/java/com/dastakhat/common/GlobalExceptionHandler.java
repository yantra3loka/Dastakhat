package com.dastakhat.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralised error handling. Every error response uses the same shape so the SPA can
 * display a clear message without guessing.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldErrorEntry(String field, String message) { }

    public record ErrorResponse(int status, String error, String message, List<FieldErrorEntry> fieldErrors,
                                 String code, Instant timestamp) { }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorEntry> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldErrorEntry(fe.getField(), fe.getDefaultMessage()));
        }
        String message = errors.isEmpty() ? "Validation failed." : errors.get(0).message();
        return build(HttpStatus.UNPROCESSABLE_ENTITY.value(), "validation_failed", message, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        List<FieldErrorEntry> errors = new ArrayList<>();
        ex.getConstraintViolations().forEach(v ->
                errors.add(new FieldErrorEntry(String.valueOf(v.getPropertyPath()), v.getMessage())));
        return build(HttpStatus.UNPROCESSABLE_ENTITY.value(), "validation_failed", "Validation failed.", errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST.value(), "bad_request", ex.getMessage(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST.value(), "bad_request", "The request body could not be read.", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE.value(), "payload_too_large",
                "The uploaded file is larger than the 10 MB limit.", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED.value(), "unauthorized", "Authentication required.", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN.value(), "forbidden", "Access denied.", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND.value(), "not_found", "Resource not found.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal_error",
                "Something went wrong on the server.", null);
    }

    private ResponseEntity<ErrorResponse> build(int status, String code, String message, List<FieldErrorEntry> fieldErrors) {
        ErrorResponse body = new ErrorResponse(status, HttpStatus.valueOf(status).getReasonPhrase(), message,
                fieldErrors, code, Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}

package io.kunkun.mockserver.controller;

import io.kunkun.mockserver.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Validation error: {}", ex.getMessage());

        return ResponseEntity.badRequest().body(
                ApiResponse.error()
                        .withMessage(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex) {

        // Collect both field errors and class-level constraint violations (@AssertTrue etc.)
        String fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        String globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining(", "));

        String errors = java.util.stream.Stream.of(fieldErrors, globalErrors)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));

        logger.warn("Validation error: {}", errors);

        return ResponseEntity.badRequest().body(
                ApiResponse.error()
                        .withMessage("Validation failed: " + errors)
                        .build()
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String errors = ex.getConstraintViolations().stream()
                .map(cv -> cv.getMessage())
                .collect(Collectors.joining(", "));

        logger.warn("Constraint violation: {}", errors);

        return ResponseEntity.badRequest().body(
                ApiResponse.error()
                        .withMessage(errors)
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        String correlationId = UUID.randomUUID().toString();
        logger.error("Unhandled exception [ref: {}]", correlationId, ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error()
                        .withMessage("Internal server error (ref: " + correlationId + ")")
                        .build()
        );
    }
}

package com.example.bank.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null ? ex.getMessage() : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Request failed", ex);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", message));
    }
}

package com.uploadservers3.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, RuntimeException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(Exception e) {
        return Map.of("ok", false, "error", e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Map<String, Object> handleRSE(ResponseStatusException e) {
        return Map.of("ok", false, "error", Objects.requireNonNull(e.getReason()));
    }
}
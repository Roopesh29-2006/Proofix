package com.proofix.backend.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(
            IllegalArgumentException exception) {

        Map<String, String> response = new HashMap<>();
        response.put("error", exception.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, String>> handleGitHubApiException(
            GitHubApiException exception) {

        Map<String, String> response = new HashMap<>();
        response.put("error", exception.getMessage());

        HttpStatus status = HttpStatus.resolve(exception.getStatusCode());

        return ResponseEntity
                .status(status == null
                        ? HttpStatus.BAD_GATEWAY
                        : status)
                .body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(
            ResourceNotFoundException exception) {

        Map<String, String> response = new HashMap<>();
        response.put("error", exception.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequestException(
            BadRequestException exception) {

        Map<String, String> response = new HashMap<>();
        response.put("error", exception.getMessage());

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(
            MethodArgumentNotValidException exception) {

        Map<String, String> response = new HashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                    response.put(error.getField(), error.getDefaultMessage())
                );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    @ExceptionHandler(BadCredentialsException.class)
public ResponseEntity<Map<String, String>> handleBadCredentials(
        BadCredentialsException exception) {

    Map<String, String> response = new HashMap<>();
    response.put("error", "Invalid email or password");

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
}
}
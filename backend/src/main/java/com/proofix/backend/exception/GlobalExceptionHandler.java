package com.proofix.backend.exception;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions to HTTP statuses with a consistent {"error": "..."} body.
 *
 * <ul>
 *   <li>400: invalid input (IllegalArgumentException, BadRequestException,
 *       validation errors, malformed JSON, bad path/query values)</li>
 *   <li>401: bad credentials or missing authentication</li>
 *   <li>403: access denied, private repository on the one-off path</li>
 *   <li>404: resource not found</li>
 *   <li>409: ConflictException and database integrity violations</li>
 *   <li>GitHub failures: 404 and 429 pass through, 503 when GitHub is
 *       unreachable, everything else (including GitHub's 401/403) becomes
 *       502 so it never looks like a problem with the caller's own session</li>
 *   <li>500: anything unexpected, with a generic message (details are
 *       logged server-side only)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------
    // 400 Bad Request
    // ---------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(
            IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequestException(
            BadRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(
            HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + exception.getName() + "'");
    }

    // ---------------------------------------------------------------
    // 401 / 403
    // ---------------------------------------------------------------

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(
            BadCredentialsException exception) {
        return error(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleAuthentication(
            AuthenticationException exception) {
        return error(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(
            AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(RepositoryAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleRepositoryAccessDenied(
            RepositoryAccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    // ---------------------------------------------------------------
    // 404 / 409
    // ---------------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(
            ResourceNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(
            ConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        // Constraint names and SQL stay in the server log only.
        log.warn("Data integrity violation: {}", exception.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "The request conflicts with existing data");
    }

    // ---------------------------------------------------------------
    // GitHub upstream failures
    // ---------------------------------------------------------------

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, String>> handleGitHubApiException(
            GitHubApiException exception) {
        HttpStatus status = switch (exception.getStatusCode()) {
            case 404 -> HttpStatus.NOT_FOUND;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return error(status, exception.getMessage());
    }

    // ---------------------------------------------------------------
    // Everything else
    // ---------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(
            Exception exception) {
        // Spring MVC exceptions (405, 406, 415, missing parameter, no
        // handler found, ResponseStatusException, ...) keep their own status.
        if (exception instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(status.value());
            return error(status, resolved == null
                    ? "Request failed"
                    : resolved.getReasonPhrase());
        }

        log.error("Unhandled exception", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private static ResponseEntity<Map<String, String>> error(
            HttpStatusCode status,
            String message) {
        Map<String, String> response = new HashMap<>();
        response.put("error", message);
        return ResponseEntity.status(status).body(response);
    }
}

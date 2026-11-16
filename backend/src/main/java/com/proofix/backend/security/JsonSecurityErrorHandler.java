package com.proofix.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * JSON responses for security failures, in the same {"error": "..."} shape
 * as GlobalExceptionHandler: 401 when a request is not authenticated (no,
 * malformed, expired or forged token) and 403 when access is denied.
 * Exception details are never written to the response.
 */
@Component
public class JsonSecurityErrorHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String UNAUTHENTICATED_BODY =
            "{\"error\":\"Authentication required\"}";
    static final String ACCESS_DENIED_BODY = "{\"error\":\"Access denied\"}";

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHENTICATED_BODY);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, ACCESS_DENIED_BODY);
    }

    private static void write(
            HttpServletResponse response,
            int status,
            String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}

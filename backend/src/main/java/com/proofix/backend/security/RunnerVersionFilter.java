package com.proofix.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runner chain only, after authentication: {@code X-Runner-Version} must be
 * a MAJOR.MINOR.PATCH at least the configured minimum. Missing, malformed or
 * older -> 426 with the minimum in {@code X-Runner-Min-Version}. Not a bean.
 */
public class RunnerVersionFilter extends OncePerRequestFilter {

    public static final String VERSION_HEADER = "X-Runner-Version";
    public static final String MIN_VERSION_HEADER = "X-Runner-Min-Version";
    private static final int UPGRADE_REQUIRED = 426;

    private final RunnerVersion minimum;

    public RunnerVersionFilter(RunnerVersion minimum) {
        this.minimum = minimum;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Optional<RunnerVersion> version = RunnerVersion.parse(request.getHeader(VERSION_HEADER));
        if (version.isEmpty() || version.get().compareTo(minimum) < 0) {
            response.setStatus(UPGRADE_REQUIRED);
            response.setHeader(MIN_VERSION_HEADER, minimum.toString());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"error\":\"Runner version " + minimum + " or newer is required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

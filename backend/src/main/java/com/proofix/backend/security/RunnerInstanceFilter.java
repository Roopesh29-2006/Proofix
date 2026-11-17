package com.proofix.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runner chain only, after the key and version checks: X-Runner-Instance
 * must match {@code ^[A-Za-z0-9._-]{1,63}$}, otherwise 400 before any Run is
 * looked up. The validated value goes into the {@link RunnerPrincipal};
 * runs.runner_id is "keyId:instanceId". Not a bean.
 */
public class RunnerInstanceFilter extends OncePerRequestFilter {

    public static final String INSTANCE_HEADER = "X-Runner-Instance";
    public static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9._-]{1,63}");
    static final String BAD_INSTANCE_BODY =
            "{\"error\":\"X-Runner-Instance header must be 1-63 characters of A-Z a-z 0-9 . _ -\"}";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String instance = request.getHeader(INSTANCE_HEADER);
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (instance == null || !INSTANCE_ID.matcher(instance).matches()
                || current == null || !(current.getPrincipal() instanceof RunnerPrincipal runner)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(BAD_INSTANCE_BODY);
            return;
        }

        UsernamePasswordAuthenticationToken withInstance =
                UsernamePasswordAuthenticationToken.authenticated(
                        runner.withInstance(instance), null, current.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(withInstance);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}

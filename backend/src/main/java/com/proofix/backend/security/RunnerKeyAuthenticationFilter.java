package com.proofix.backend.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import com.proofix.backend.runnerkey.RunnerKeyCodec;
import com.proofix.backend.runnerkey.RunnerKeyIdentity;
import com.proofix.backend.runnerkey.RunnerKeyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates /api/runner/** with a runner key ({@code Authorization:
 * Bearer dbr_...}) and nothing else. Anything that is not a well-formed key
 * (a JWT included) is answered 401 at once, before any database lookup; a
 * well-formed key is looked up on every request, so revoked and expired keys
 * fail immediately.
 *
 * <p>Not a Spring bean: it is created by SecurityConfig for the runner chain
 * only, so it can never run as a global servlet filter. Never logs the
 * header, the key or its hash.
 */
public class RunnerKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "RUNNER";
    private static final String BEARER = "Bearer ";

    private final RunnerKeyService keys;
    private final AuthenticationEntryPoint entryPoint;

    public RunnerKeyAuthenticationFilter(RunnerKeyService keys, AuthenticationEntryPoint entryPoint) {
        this.keys = keys;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Optional<RunnerKeyIdentity> identity = Optional.empty();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String key = header.substring(BEARER.length());
            // Prefix, exact length and alphabet are checked before any lookup.
            if (RunnerKeyCodec.looksLikeKey(key)) {
                identity = keys.authenticate(key);
            }
        }

        if (identity.isEmpty()) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response,
                    new BadCredentialsException("Runner key required"));
            return;
        }

        RunnerKeyIdentity id = identity.get();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new RunnerPrincipal(id.keyId(), id.ownerUserId()), null,
                        List.of(new SimpleGrantedAuthority(AUTHORITY)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}

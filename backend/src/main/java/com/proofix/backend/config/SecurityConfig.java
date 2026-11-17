
package com.proofix.backend.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.proofix.backend.run.runner.RunnerApiProperties;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.security.JsonSecurityErrorHandler;
import com.proofix.backend.security.JwtAuthenticationFilter;
import com.proofix.backend.security.RequestBodyLimitFilter;
import com.proofix.backend.security.RunnerInstanceFilter;
import com.proofix.backend.security.RunnerKeyAuthenticationFilter;
import com.proofix.backend.security.RunnerVersionFilter;

import jakarta.servlet.DispatcherType;

/**
 * Two chains. {@code /api/runner/**} (order 1) accepts runner keys only:
 * runner key (401), version header (426), instance header (400), body cap
 * (413), in that order; the JWT filter is
 * not in it. Everything else (order 2) accepts JWTs only; the runner filters
 * are not in it. None of these filters is registered as a global servlet
 * filter.
 */
@Configuration
public class SecurityConfig {

    static final String RUNNER_API = "/api/runner/**";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonSecurityErrorHandler securityErrorHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonSecurityErrorHandler securityErrorHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityErrorHandler = securityErrorHandler;
    }

    /**
     * The JWT filter is a bean, so Spring Boot would also register it as a
     * global servlet filter that runs outside (and before) the security
     * chains. It must run only inside its own chain.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterServletRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Runner API. The filters are created here (not beans), so they exist
     * only in this chain. When {@code devbuddy.runner.api.enabled=false}
     * every request is refused (401).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain runnerFilterChain(
            HttpSecurity http,
            RunnerKeyService runnerKeys,
            RunnerApiProperties runnerApi) throws Exception {

        http
            .securityMatcher(RUNNER_API)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(securityErrorHandler)
                .accessDeniedHandler(securityErrorHandler));

        if (!runnerApi.enabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
            return http.build();
        }

        RunnerKeyAuthenticationFilter keyFilter =
                new RunnerKeyAuthenticationFilter(runnerKeys, securityErrorHandler);
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().hasAuthority(RunnerKeyAuthenticationFilter.AUTHORITY))
            .addFilterBefore(keyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new RunnerVersionFilter(runnerApi.minimumVersion()),
                RunnerKeyAuthenticationFilter.class)
            .addFilterAfter(new RunnerInstanceFilter(), RunnerVersionFilter.class)
            .addFilterAfter(new RequestBodyLimitFilter(runnerApi.maxBodyBytes()),
                RunnerInstanceFilter.class);

        return http.build();
    }

    /** User API (JWT). */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
            // Disable CSRF for stateless REST APIs
            .csrf(csrf -> csrf.disable())

            // Do not create HTTP sessions
            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )

            // Define public and protected endpoints
            .authorizeHttpRequests(auth -> auth

                // Error dispatches carry the original status; without this the
                // JWT filter (which skips error dispatches) would turn every
                // error response into a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                .requestMatchers(
                    HttpMethod.POST,
                    "/api/auth/register",
                    "/api/auth/login"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET,
                    "/actuator/health"
                ).permitAll()

                .anyRequest().authenticated()
            )

            // JSON 401 (not authenticated) and 403 (access denied)
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(securityErrorHandler)
                .accessDeniedHandler(securityErrorHandler)
            )

            // Execute JWT filter before username/password authentication
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }
}

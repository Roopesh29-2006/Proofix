package com.proofix.backend.run.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.validation.Valid;

/**
 * /api/runs. All endpoints require authentication (SecurityConfig); the
 * caller only ever sees their own Runs.
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateRunRequest request) {
        RunResponse run = runService.create(userId(authentication), request.repoUrl());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(run.id()).toUri();
        return ResponseEntity.created(location).body(run);
    }

    @GetMapping("/{id}")
    public RunResponse get(Authentication authentication, @PathVariable UUID id) {
        return runService.get(userId(authentication), id);
    }

    @GetMapping
    public RunPageResponse list(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return runService.list(userId(authentication), page, size);
    }

    @PostMapping("/{id}/cancel")
    public RunResponse cancel(Authentication authentication, @PathVariable UUID id) {
        return runService.cancel(userId(authentication), id);
    }

    @PostMapping("/{id}/approve")
    public RunResponse approve(Authentication authentication, @PathVariable UUID id) {
        return runService.approve(userId(authentication), id);
    }

    @PostMapping("/{id}/reject")
    public RunResponse reject(Authentication authentication, @PathVariable UUID id) {
        return runService.reject(userId(authentication), id);
    }

    /** The JWT filter puts the user id (token subject) in as the principal name. */
    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

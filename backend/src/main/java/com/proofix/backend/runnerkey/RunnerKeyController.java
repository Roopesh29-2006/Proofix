package com.proofix.backend.runnerkey;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * /api/runner-keys: the signed-in user's runner keys (JWT only). The
 * plaintext key is in the 201 of create and nowhere else.
 */
@RestController
@RequestMapping("/api/runner-keys")
public class RunnerKeyController {

    private final RunnerKeyService service;

    public RunnerKeyController(RunnerKeyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreatedRunnerKeyResponse> create(
            Authentication authentication,
            @RequestBody CreateRunnerKeyRequest request) {
        CreatedRunnerKey created = service.create(userId(authentication), request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.key().getId()).toUri();
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(CreatedRunnerKeyResponse.from(created));
    }

    @GetMapping
    public List<RunnerKeyResponse> list(Authentication authentication) {
        OffsetDateTime now = service.now();
        return service.list(userId(authentication)).stream()
                .map(key -> RunnerKeyResponse.from(key, now))
                .toList();
    }

    @PostMapping("/{id}/revoke")
    public RunnerKeyResponse revoke(Authentication authentication, @PathVariable UUID id) {
        return RunnerKeyResponse.from(service.revoke(userId(authentication), id), service.now());
    }

    /** The JWT filter puts the user id (token subject) in as the principal name. */
    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}

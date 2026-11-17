package com.proofix.backend.run.runner;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.run.RunnerClaim;
import com.proofix.backend.security.RunnerPrincipal;

import tools.jackson.databind.JsonNode;

/**
 * /api/runner/**: the external runner's view of its owner's Runs. Reached
 * only through the runner security chain (runner key + version header +
 * body cap). The owner and runner id come from the key, never the body.
 */
@RestController
@RequestMapping("/api/runner")
public class RunnerApiController {

    static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9._-]{1,63}");

    private final RunWorkerOps ops;

    public RunnerApiController(RunWorkerOps ops) {
        this.ops = ops;
    }

    /** Claims the owner's oldest QUEUED Run (-> ANALYZING), or 204 if none waits. */
    @PostMapping("/claim")
    public ResponseEntity<RunnerClaimResponse> claim(
            @AuthenticationPrincipal RunnerPrincipal runner,
            @RequestBody(required = false) byte[] body) {
        JsonNode request = StrictJson.readObject(body, Set.of("instanceId"));
        String instanceId = StrictJson.requiredString(request, "instanceId", 63, INSTANCE_ID);

        return ops.claimNextQueuedForOwner(runner.ownerUserId(), runner.runnerId(instanceId))
                .map(claim -> ResponseEntity.ok(RunnerClaimResponse.from(claim)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record RunnerClaimResponse(UUID runId, String repoUrl, String status) {

        static RunnerClaimResponse from(RunnerClaim claim) {
            return new RunnerClaimResponse(claim.runId(), claim.repoUrl(), claim.status().name());
        }
    }
}

package com.proofix.backend.run.runner;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.run.FailureCode;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.run.RunnerClaim;
import com.proofix.backend.run.RunnerHeartbeat;
import com.proofix.backend.security.RunnerPrincipal;

import tools.jackson.databind.JsonNode;

/**
 * /api/runner/**: the external runner's view of its owner's Runs. Reached
 * only through the runner security chain (runner key, version header,
 * instance header, body cap). The owner and runner id ("keyId:instanceId")
 * come from the key and the header, never from a body. Every per-Run call
 * that is not for a Run this runner holds (or held) gets the same 404 as a
 * missing Run.
 */
@RestController
@RequestMapping("/api/runner")
public class RunnerApiController {

    private static final String RUNNER_CODES = Arrays.stream(FailureCode.values())
            .filter(FailureCode::isRunnerReportable).map(Enum::name)
            .collect(Collectors.joining(", "));

    private final RunWorkerOps ops;
    private final RunPlanParser planParser;
    private final FailReasonSanitizer reasonSanitizer;

    public RunnerApiController(RunWorkerOps ops, RunPlanParser planParser,
            FailReasonSanitizer reasonSanitizer) {
        this.ops = ops;
        this.planParser = planParser;
        this.reasonSanitizer = reasonSanitizer;
    }

    /** Claims the owner's oldest QUEUED Run (-> ANALYZING), or 204 if none waits. Body: none or {}. */
    @PostMapping("/claim")
    public ResponseEntity<RunnerClaimResponse> claim(
            @AuthenticationPrincipal RunnerPrincipal runner,
            @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) {
            StrictJson.readObject(body, Set.of());
        }
        return ops.claimNextQueuedForOwner(runner.ownerUserId(), runner.runnerId())
                .map(claim -> ResponseEntity.ok(RunnerClaimResponse.from(claim)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Liveness; the answer tells the runner whether to stop. No body. */
    @PostMapping("/runs/{id}/heartbeat")
    public HeartbeatResponse heartbeat(
            @AuthenticationPrincipal RunnerPrincipal runner,
            @PathVariable UUID id,
            @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) {
            throw new BadRequestException("heartbeat takes no request body");
        }
        RunnerHeartbeat beat = ops.heartbeatRunner(runner.ownerUserId(), id, runner.runnerId());
        return new HeartbeatResponse(beat.cancelRequested(), beat.status().name());
    }

    /** ANALYZING -> AWAITING_PLAN_OK with the validated plan; a pending cancel wins. */
    @PostMapping("/runs/{id}/report-analysis")
    public StatusResponse reportAnalysis(
            @AuthenticationPrincipal RunnerPrincipal runner,
            @PathVariable UUID id,
            @RequestBody(required = false) byte[] body) {
        RunPlanParser.ParsedPlan plan = planParser.parse(body);
        return new StatusResponse(
                ops.reportPlan(runner.ownerUserId(), id, runner.runnerId(), plan.json()).name());
    }

    /** -> FAILED with a runner code and a sanitized reason. The hint is the server's. */
    @PostMapping("/runs/{id}/fail")
    public StatusResponse fail(
            @AuthenticationPrincipal RunnerPrincipal runner,
            @PathVariable UUID id,
            @RequestBody(required = false) byte[] body) {
        JsonNode request = StrictJson.readObject(body, Set.of("code", "reason"));
        JsonNode codeNode = request.get("code");
        FailureCode code = codeNode != null && codeNode.isString()
                ? FailureCode.runnerReportable(codeNode.stringValue()).orElse(null)
                : null;
        if (code == null) {
            throw new BadRequestException("code must be one of: " + RUNNER_CODES);
        }
        JsonNode reasonNode = request.get("reason");
        if (reasonNode == null || !reasonNode.isString()) {
            throw new BadRequestException("reason must be a string");
        }
        String reason = reasonSanitizer.sanitize(reasonNode.stringValue());
        return new StatusResponse(
                ops.failRunner(runner.ownerUserId(), id, runner.runnerId(), code, reason).name());
    }

    public record RunnerClaimResponse(UUID runId, String repoUrl, String status) {

        static RunnerClaimResponse from(RunnerClaim claim) {
            return new RunnerClaimResponse(claim.runId(), claim.repoUrl(), claim.status().name());
        }
    }

    public record HeartbeatResponse(boolean cancelRequested, String status) {
    }

    public record StatusResponse(String status) {
    }
}

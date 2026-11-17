package com.proofix.backend.run;

import static com.proofix.backend.run.RunStatus.ANALYZING;
import static com.proofix.backend.run.RunStatus.CANCELLED;
import static com.proofix.backend.run.RunStatus.FAILED;
import static com.proofix.backend.run.RunStatus.QUEUED;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The worker's view of a Run. Every method is one short transaction that
 * locks the Run row and re-checks ownership (runner_id) and status before
 * acting, so a worker that was reaped or cancelled cannot move a Run it no
 * longer owns. Status changes go through {@link RunStateMachine#transition}.
 */
@Service
public class RunWorkerOps {

    static final String CANCEL_NOTE = "cancel requested";
    static final String HEARTBEAT_LOST = "worker heartbeat lost";
    static final String OVER_BUDGET = "max_minutes exceeded";

    private final RunRepository runRepository;
    private final RunStateMachine stateMachine;
    private final Clock clock;

    public RunWorkerOps(RunRepository runRepository, RunStateMachine stateMachine, Clock clock) {
        this.runRepository = runRepository;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Claim and liveness
    // ------------------------------------------------------------------

    /**
     * Claims the oldest Run waiting for a worker (SKIP LOCKED). A QUEUED Run
     * moves to ANALYZING; an approved TESTING Run keeps its status. Either
     * way runner_id and heartbeat_at are set in the same transaction.
     */
    @Transactional
    public Optional<ClaimedRun> claimNext(String runnerId) {
        requireRunner(runnerId);
        Optional<UUID> next = runRepository.lockNextClaimableId();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        UUID runId = next.get();
        Run run = runRepository.findByIdForUpdate(runId).orElseThrow();
        if (run.getStatus() == QUEUED) {
            run = stateMachine.transition(runId, ANALYZING, RunActor.WORKER,
                    "claimed by " + runnerId);
        }
        run.setRunnerId(runnerId);
        run.setHeartbeatAt(now());
        runRepository.save(run);
        return Optional.of(new ClaimedRun(runId, runnerId, run.getStatus()));
    }

    /**
     * Runner API claim: the oldest QUEUED Run of {@code owner} only (SKIP
     * LOCKED), moved to ANALYZING with runner_id and heartbeat_at set in the
     * same transaction. {@code owner} and {@code runnerId} come from the
     * authenticated runner key, never from a request body.
     */
    @Transactional
    public Optional<RunnerClaim> claimNextQueuedForOwner(UUID owner, String runnerId) {
        if (owner == null) {
            throw new IllegalArgumentException("owner is required");
        }
        requireRunner(runnerId);
        Optional<UUID> next = runRepository.lockNextQueuedIdForOwner(owner);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        UUID runId = next.get();
        Run run = stateMachine.transition(runId, ANALYZING, RunActor.WORKER,
                "claimed by runner " + runnerId);
        run.setRunnerId(runnerId);
        run.setHeartbeatAt(now());
        runRepository.save(run);
        return Optional.of(new RunnerClaim(runId, run.getRepoUrl(), run.getStatus()));
    }

    /** Liveness only (no lock wait, no status change). False if not ours any more. */
    @Transactional
    public boolean heartbeat(UUID runId, String runnerId) {
        return runRepository.touchHeartbeat(runId, runnerId, now()) == 1;
    }

    // ------------------------------------------------------------------
    // Locked steps
    // ------------------------------------------------------------------

    /** Between steps: stop if the Run is no longer ours, cancel if requested. */
    @Transactional
    public StepOutcome checkpoint(UUID runId, String runnerId) {
        Run run = ownedActive(runId, runnerId);
        if (run == null) {
            return StepOutcome.LOST;
        }
        if (run.isCancelRequested()) {
            stateMachine.transition(runId, CANCELLED, RunActor.WORKER, CANCEL_NOTE);
            return StepOutcome.CANCELLED;
        }
        return StepOutcome.CONTINUE;
    }

    /**
     * Moves a Run this runner owns from {@code from} to {@code to}. If
     * cancel_requested is set, the Run goes to CANCELLED instead, decided
     * under the same lock: in particular it never reaches AWAITING_* with a
     * pending cancel.
     */
    @Transactional
    public StepOutcome advance(UUID runId, String runnerId, RunStatus from, RunStatus to) {
        Run run = ownedActive(runId, runnerId);
        if (run == null || run.getStatus() != from) {
            return StepOutcome.LOST;
        }
        if (run.isCancelRequested()) {
            stateMachine.transition(runId, CANCELLED, RunActor.WORKER, CANCEL_NOTE);
            return StepOutcome.CANCELLED;
        }
        stateMachine.transition(runId, to, RunActor.WORKER, null);
        return StepOutcome.MOVED;
    }

    /** The executor gave up on a Run it owns. */
    @Transactional
    public StepOutcome fail(UUID runId, String runnerId, String reason) {
        if (ownedActive(runId, runnerId) == null) {
            return StepOutcome.LOST;
        }
        stateMachine.transition(runId, FAILED, RunActor.WORKER, reason);
        return StepOutcome.MOVED;
    }

    // ------------------------------------------------------------------
    // Reaper steps (re-check under the lock; the candidate query may be stale)
    // ------------------------------------------------------------------

    /** Fails an active Run with a runner whose heartbeat is older than cutoff. */
    @Transactional
    public boolean reapStaleHeartbeat(UUID runId, OffsetDateTime cutoff) {
        Run run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || !run.getStatus().isActive() || run.getRunnerId() == null) {
            return false;
        }
        if (run.getHeartbeatAt() != null && !run.getHeartbeatAt().isBefore(cutoff)) {
            return false;
        }
        stateMachine.transition(runId, FAILED, RunActor.SYSTEM, HEARTBEAT_LOST);
        return true;
    }

    /** Fails an active Run (runner or not) whose worker time exceeds max_minutes. */
    @Transactional
    public boolean reapOverBudget(UUID runId, Instant now) {
        Run run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || !run.getStatus().isActive() || !run.exceedsMaxMinutes(now)) {
            return false;
        }
        stateMachine.transition(runId, FAILED, RunActor.SYSTEM, OVER_BUDGET);
        return true;
    }

    // ------------------------------------------------------------------

    private Run ownedActive(UUID runId, String runnerId) {
        requireRunner(runnerId);
        Run run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || !run.getStatus().isActive() || !runnerId.equals(run.getRunnerId())) {
            return null;
        }
        return run;
    }

    private static void requireRunner(String runnerId) {
        if (runnerId == null || runnerId.isBlank()) {
            throw new IllegalArgumentException("runnerId is required");
        }
    }

    private OffsetDateTime now() {
        return RunTime.now(clock);
    }
}

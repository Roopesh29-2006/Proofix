package com.proofix.backend.run;

import static com.proofix.backend.run.RunStatus.ANALYZING;
import static com.proofix.backend.run.RunStatus.AWAITING_FIX_OK;
import static com.proofix.backend.run.RunStatus.AWAITING_PLAN_OK;
import static com.proofix.backend.run.RunStatus.CANCELLED;
import static com.proofix.backend.run.RunStatus.DONE;
import static com.proofix.backend.run.RunStatus.INVESTIGATING;
import static com.proofix.backend.run.RunStatus.QUEUED;
import static com.proofix.backend.run.RunStatus.TESTING;

import java.time.Clock;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.exception.ResourceNotFoundException;

/**
 * User-facing operations on Runs. Every lookup is scoped to the owner, so
 * another user's Run is "not found" (404), never "forbidden". Status changes
 * are delegated to {@link RunStateMachine#transition}.
 */
@Service
public class RunLifecycle {

    /** Count toward {@link RunLimits#maxActive}: QUEUED and active. */
    public static final Set<RunStatus> ACTIVE_CAP_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(QUEUED, ANALYZING, TESTING, INVESTIGATING));

    /** Count toward {@link RunLimits#maxUnfinished}: everything not final. */
    public static final Set<RunStatus> UNFINISHED_STATUSES = Collections.unmodifiableSet(
            EnumSet.of(QUEUED, ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING, AWAITING_FIX_OK));

    private static final String NOT_FOUND = "Run not found";

    private final RunRepository runRepository;
    private final RunStateMachine stateMachine;
    private final Clock clock;

    public RunLifecycle(RunRepository runRepository, RunStateMachine stateMachine, Clock clock) {
        this.runRepository = runRepository;
        this.stateMachine = stateMachine;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * Cheap pre-check without a lock, so deleted users and over-cap requests
     * are refused before any GitHub call. Not authoritative;
     * {@link #createWithinLimits} is.
     */
    @Transactional(readOnly = true)
    public void checkCreateLimits(UUID owner, RunLimits limits) {
        if (!runRepository.ownerExists(owner)) {
            throw unknownUser();
        }
        enforceCreateLimits(owner, limits);
    }

    /**
     * Locks the owner's row, re-counts and creates the QUEUED Run in one
     * transaction, so two parallel requests at the boundary cannot both pass.
     */
    @Transactional
    public Run createWithinLimits(UUID owner, String repoUrl, RunBudgets budgets, RunLimits limits) {
        if (runRepository.lockOwner(owner).isEmpty()) {
            throw unknownUser();
        }
        enforceCreateLimits(owner, limits);
        return stateMachine.create(owner, repoUrl, budgets);
    }

    private void enforceCreateLimits(UUID owner, RunLimits limits) {
        if (runRepository.countByOwnerUserIdAndStatusIn(owner, ACTIVE_CAP_STATUSES)
                >= limits.maxActive()) {
            throw activeCapExceeded(limits.maxActive());
        }
        if (runRepository.countByOwnerUserIdAndStatusIn(owner, UNFINISHED_STATUSES)
                >= limits.maxUnfinished()) {
            throw new ConflictException("You already have " + limits.maxUnfinished()
                    + " unfinished Runs (the limit), including Runs waiting for your approval. "
                    + "Approve, reject or cancel one first.");
        }
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Run get(UUID owner, UUID runId) {
        return runRepository.findByIdAndOwnerUserId(runId, owner)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<Run> list(UUID owner, Pageable pageable) {
        return runRepository.findByOwnerUserId(owner, pageable);
    }

    // ------------------------------------------------------------------
    // Cancel / approve / reject (decided under the Run's row lock)
    // ------------------------------------------------------------------

    /**
     * QUEUED, AWAITING_*: cancelled now. Active with a runner: only
     * cancel_requested is set; the worker stops and cancels. Active without a
     * runner (no worker to notice the flag): cancelled now. Final: 409.
     */
    @Transactional
    public Run cancel(UUID owner, UUID runId) {
        Run run = lockOwned(owner, runId);
        RunStatus status = run.getStatus();

        if (status.isFinal()) {
            throw new IllegalRunTransitionException(status, CANCELLED);
        }
        if (status.isActive() && run.getRunnerId() != null) {
            if (!run.isCancelRequested()) {
                run.requestCancel(RunTime.now(clock));
                return runRepository.save(run);
            }
            return run;
        }
        return stateMachine.transition(runId, CANCELLED, RunActor.USER, "cancelled by user");
    }

    /**
     * Plan approval puts the Run back into active work, so the active cap is
     * re-checked under the owner's row lock (taken before the Run lock, the
     * same order as create, so the two cannot deadlock).
     */
    @Transactional
    public Run approve(UUID owner, UUID runId, RunLimits limits) {
        if (runRepository.lockOwner(owner).isEmpty()) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        Run run = lockOwned(owner, runId);
        return switch (run.getStatus()) {
            case AWAITING_PLAN_OK -> {
                if (runRepository.countByOwnerUserIdAndStatusIn(owner, ACTIVE_CAP_STATUSES)
                        >= limits.maxActive()) {
                    throw activeCapExceeded(limits.maxActive());
                }
                yield stateMachine.transition(runId, TESTING, RunActor.USER, "plan approved by user");
            }
            case AWAITING_FIX_OK ->
                    stateMachine.transition(runId, DONE, RunActor.USER, "fix approved by user");
            default -> throw notAwaiting(run);
        };
    }

    @Transactional
    public Run reject(UUID owner, UUID runId) {
        Run run = lockOwned(owner, runId);
        return switch (run.getStatus()) {
            case AWAITING_PLAN_OK ->
                    stateMachine.transition(runId, CANCELLED, RunActor.USER, "rejected by user");
            case AWAITING_FIX_OK ->
                    // The investigation is complete; only the patch is declined.
                    stateMachine.transition(runId, DONE, RunActor.USER, "fix declined by user");
            default -> throw notAwaiting(run);
        };
    }

    private Run lockOwned(UUID owner, UUID runId) {
        return runRepository.findByIdAndOwnerForUpdate(runId, owner)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private static AuthenticationCredentialsNotFoundException unknownUser() {
        return new AuthenticationCredentialsNotFoundException("User authentication is required");
    }

    private static ConflictException activeCapExceeded(int cap) {
        return new ConflictException("You already have " + cap
                + " queued or running Runs (the limit). Wait for one to finish or cancel one.");
    }

    private static ConflictException notAwaiting(Run run) {
        return new ConflictException("Run is not awaiting approval (status " + run.getStatus() + ")");
    }
}

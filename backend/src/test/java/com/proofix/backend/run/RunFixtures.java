package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

/**
 * Worker-state fixtures reached only through legal steps: claim, worker
 * advance, user approve. Requires an otherwise empty claim queue (claiming
 * picks the oldest claimable Run globally), see RunTestUsers.purgeAllRuns.
 */
final class RunFixtures {

    static final RunBudgets BUDGETS = new RunBudgets(30, 50, 100);
    static final RunLimits NO_LIMITS = new RunLimits(1_000, 1_000);

    private final RunStateMachine machine;
    private final RunWorkerOps ops;
    private final RunLifecycle lifecycle;
    private final RunRepository runRepository;

    RunFixtures(RunStateMachine machine, RunWorkerOps ops, RunLifecycle lifecycle,
            RunRepository runRepository) {
        this.machine = machine;
        this.ops = ops;
        this.lifecycle = lifecycle;
        this.runRepository = runRepository;
    }

    Run queued(UUID owner) {
        return machine.create(owner, "https://github.com/octo/demo", BUDGETS);
    }

    ClaimedRun claimedAnalyzing(UUID owner, String runner) {
        Run run = queued(owner);
        ClaimedRun claim = ops.claimNext(runner).orElseThrow();
        assertEquals(run.getId(), claim.runId(), "claim queue was not empty");
        assertEquals(RunStatus.ANALYZING, claim.status());
        return claim;
    }

    Run unclaimedTesting(UUID owner, String runner) {
        ClaimedRun claim = claimedAnalyzing(owner, runner);
        assertEquals(StepOutcome.MOVED, ops.advance(
                claim.runId(), runner, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK));
        return lifecycle.approve(owner, claim.runId(), NO_LIMITS);
    }

    ClaimedRun claimedTesting(UUID owner, String runner) {
        Run run = unclaimedTesting(owner, runner);
        ClaimedRun claim = ops.claimNext(runner).orElseThrow();
        assertEquals(run.getId(), claim.runId());
        assertEquals(RunStatus.TESTING, claim.status());
        return claim;
    }

    ClaimedRun claimedInvestigating(UUID owner, String runner) {
        ClaimedRun claim = claimedTesting(owner, runner);
        assertEquals(StepOutcome.MOVED, ops.advance(
                claim.runId(), runner, RunStatus.TESTING, RunStatus.INVESTIGATING));
        return new ClaimedRun(claim.runId(), runner, RunStatus.INVESTIGATING);
    }

    /** A claimed Run in the given active state. */
    ClaimedRun claimedIn(RunStatus status, UUID owner, String runner) {
        return switch (status) {
            case ANALYZING -> claimedAnalyzing(owner, runner);
            case TESTING -> claimedTesting(owner, runner);
            case INVESTIGATING -> claimedInvestigating(owner, runner);
            default -> throw new IllegalArgumentException(status.name());
        };
    }

    Run reload(UUID runId) {
        return runRepository.findById(runId).orElseThrow();
    }
}

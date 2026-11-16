package com.proofix.backend.run.worker;

import static com.proofix.backend.run.RunStatus.ANALYZING;
import static com.proofix.backend.run.RunStatus.AWAITING_FIX_OK;
import static com.proofix.backend.run.RunStatus.AWAITING_PLAN_OK;
import static com.proofix.backend.run.RunStatus.INVESTIGATING;
import static com.proofix.backend.run.RunStatus.TESTING;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.proofix.backend.run.ClaimedRun;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.run.StepOutcome;

/**
 * Simulates the work with sleeps: ANALYZING then stop at AWAITING_PLAN_OK;
 * after approval, TESTING and INVESTIGATING then stop at AWAITING_FIX_OK.
 * Heartbeats after every step and checks for cancel between steps. No AI,
 * k6 or Docker. Only exists when {@code devbuddy.runner.fake.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "devbuddy.runner.fake", name = "enabled", havingValue = "true")
public class FakeRunExecutor implements RunExecutor {

    private final RunWorkerOps ops;
    private final RunnerProperties.Fake settings;

    public FakeRunExecutor(RunWorkerOps ops, RunnerProperties properties) {
        this.ops = ops;
        this.settings = properties.fake();
    }

    @Override
    public void execute(ClaimedRun claim) {
        switch (claim.status()) {
            case ANALYZING -> {
                if (work(claim) == StepOutcome.CONTINUE) {
                    ops.advance(claim.runId(), claim.runnerId(), ANALYZING, AWAITING_PLAN_OK);
                }
            }
            case TESTING -> {
                if (work(claim) != StepOutcome.CONTINUE) {
                    return;
                }
                if (ops.advance(claim.runId(), claim.runnerId(), TESTING, INVESTIGATING)
                        != StepOutcome.MOVED) {
                    return;
                }
                if (work(claim) == StepOutcome.CONTINUE) {
                    ops.advance(claim.runId(), claim.runnerId(), INVESTIGATING, AWAITING_FIX_OK);
                }
            }
            default -> {
                // Nothing to do for other states.
            }
        }
    }

    /** One simulated phase: steps of sleep, each followed by heartbeat and cancel check. */
    private StepOutcome work(ClaimedRun claim) {
        for (int step = 0; step < settings.stepsPerPhase(); step++) {
            try {
                Thread.sleep(settings.stepMs());
            } catch (InterruptedException e) {
                // Shutting down: leave the Run; the reaper fails it on a lost heartbeat.
                Thread.currentThread().interrupt();
                return StepOutcome.LOST;
            }
            if (!ops.heartbeat(claim.runId(), claim.runnerId())) {
                return StepOutcome.LOST;
            }
            StepOutcome outcome = ops.checkpoint(claim.runId(), claim.runnerId());
            if (outcome != StepOutcome.CONTINUE) {
                return outcome;
            }
        }
        return StepOutcome.CONTINUE;
    }
}

package com.proofix.backend.run.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.proofix.backend.run.ClaimedRun;
import com.proofix.backend.run.RunStatus;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.run.StepOutcome;

class RunPollerTest {

    private static final RunnerProperties PROPS = new RunnerProperties(
            "runner-x", 2_000, 10_000, 30_000, new RunnerProperties.Fake(false, 500, 3));

    private final RunWorkerOps ops = mock(RunWorkerOps.class);
    private final RunExecutor executor = mock(RunExecutor.class);

    private RunPoller poller(RunExecutor executorOrNull) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (executorOrNull != null) {
            beans.addBean("executor", executorOrNull);
        }
        return new RunPoller(ops, beans.getBeanProvider(RunExecutor.class), PROPS);
    }

    @Test
    void withoutAnExecutorNothingIsClaimed() {
        assertFalse(poller(null).pollOnce());
        verify(ops, never()).claimNext(anyString());
    }

    @Test
    void claimsAndHandsTheRunToTheExecutor() {
        ClaimedRun claim = new ClaimedRun(UUID.randomUUID(), "runner-x", RunStatus.ANALYZING);
        when(ops.claimNext("runner-x")).thenReturn(Optional.of(claim));

        assertTrue(poller(executor).pollOnce());

        verify(executor).execute(claim);
    }

    @Test
    void emptyQueueDoesNothing() {
        when(ops.claimNext("runner-x")).thenReturn(Optional.empty());

        assertFalse(poller(executor).pollOnce());
        verify(executor, never()).execute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executorCrashFailsTheRunWithAGenericReason() {
        ClaimedRun claim = new ClaimedRun(UUID.randomUUID(), "runner-x", RunStatus.ANALYZING);
        when(ops.claimNext("runner-x")).thenReturn(Optional.of(claim));
        when(ops.fail(eq(claim.runId()), eq("runner-x"), anyString())).thenReturn(StepOutcome.MOVED);
        doThrow(new IllegalStateException("secret detail")).when(executor).execute(claim);

        assertTrue(poller(executor).pollOnce());

        verify(ops).fail(claim.runId(), "runner-x", "worker error");
    }

    @Test
    void runnerIdDefaultsToARandomFakeId() {
        RunPoller poller = new RunPoller(ops, new StaticListableBeanFactory()
                .getBeanProvider(RunExecutor.class),
                new RunnerProperties(null, 2_000, 10_000, 30_000,
                        new RunnerProperties.Fake(false, 500, 3)));
        assertTrue(poller.runnerId().startsWith("runner-"));
    }
}

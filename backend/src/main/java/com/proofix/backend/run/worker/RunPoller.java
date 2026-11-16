package com.proofix.backend.run.worker;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.proofix.backend.run.ClaimedRun;
import com.proofix.backend.run.RunWorkerOps;

/**
 * Claims the next Run waiting for a worker and hands it to the
 * {@link RunExecutor}. Without an executor bean (the default) it claims
 * nothing, so Runs stay QUEUED.
 */
@Component
public class RunPoller {

    private static final Logger log = LoggerFactory.getLogger(RunPoller.class);

    private final RunWorkerOps ops;
    private final ObjectProvider<RunExecutor> executor;
    private final String runnerId;

    public RunPoller(RunWorkerOps ops, ObjectProvider<RunExecutor> executor,
            RunnerProperties properties) {
        this.ops = ops;
        this.executor = executor;
        this.runnerId = properties.id() == null || properties.id().isBlank()
                ? "runner-" + UUID.randomUUID()
                : properties.id();
    }

    @Scheduled(fixedDelayString = "${devbuddy.runner.poll-interval-ms:2000}")
    public void scheduledPoll() {
        pollOnce();
    }

    /** @return true if a Run was claimed and handed to the executor */
    public boolean pollOnce() {
        RunExecutor target = executor.getIfAvailable();
        if (target == null) {
            return false;
        }
        Optional<ClaimedRun> claim = ops.claimNext(runnerId);
        if (claim.isEmpty()) {
            return false;
        }
        ClaimedRun run = claim.get();
        try {
            target.execute(run);
        } catch (RuntimeException e) {
            // Only the type is logged and stored: messages may carry details
            // (paths, URLs, tokens) that must not leak into logs or the API.
            log.warn("Executor failed for run {}: {}", run.runId(), e.getClass().getSimpleName());
            ops.fail(run.runId(), runnerId, "worker error");
        }
        return true;
    }

    public String runnerId() {
        return runnerId;
    }
}

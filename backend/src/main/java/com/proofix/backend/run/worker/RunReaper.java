package com.proofix.backend.run.worker;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.proofix.backend.run.RunRepository;
import com.proofix.backend.run.RunWorkerOps;

/**
 * Fails Runs that will not finish on their own:
 * <ul>
 *   <li>active with a runner whose heartbeat is older than
 *       {@code devbuddy.runner.heartbeat-timeout-ms}: "worker heartbeat lost"
 *       (Runs with no runner have no heartbeat to lose);</li>
 *   <li>active, with or without a runner, whose worker time exceeds
 *       max_minutes: "max_minutes exceeded".</li>
 * </ul>
 * Candidates come from a query; each one is re-checked under its row lock.
 */
@Component
public class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);

    public record ReapResult(int heartbeatLost, int overBudget) {
    }

    private final RunRepository runRepository;
    private final RunWorkerOps ops;
    private final Clock clock;
    private final long heartbeatTimeoutMs;

    public RunReaper(RunRepository runRepository, RunWorkerOps ops, Clock clock,
            RunnerProperties properties) {
        this.runRepository = runRepository;
        this.ops = ops;
        this.clock = clock;
        this.heartbeatTimeoutMs = properties.heartbeatTimeoutMs();
    }

    @Scheduled(fixedDelayString = "${devbuddy.runner.reaper-interval-ms:10000}")
    public void scheduledReap() {
        reapOnce();
    }

    public ReapResult reapOnce() {
        Instant now = clock.instant();
        OffsetDateTime nowUtc = now.atOffset(ZoneOffset.UTC);
        OffsetDateTime cutoff = nowUtc.minusNanos(heartbeatTimeoutMs * 1_000_000);

        int heartbeatLost = 0;
        for (UUID id : runRepository.findStaleHeartbeatIds(cutoff)) {
            if (ops.reapStaleHeartbeat(id, cutoff)) {
                heartbeatLost++;
            }
        }
        int overBudget = 0;
        for (UUID id : runRepository.findOverBudgetIds(nowUtc)) {
            if (ops.reapOverBudget(id, now)) {
                overBudget++;
            }
        }
        if (heartbeatLost + overBudget > 0) {
            log.info("Reaper failed {} run(s) with a lost heartbeat and {} over max_minutes",
                    heartbeatLost, overBudget);
        }
        return new ReapResult(heartbeatLost, overBudget);
    }
}

package com.proofix.backend.run.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code devbuddy.runner.*}.
 *
 * <p>Scheduling (poller and reaper) is switched by
 * {@code devbuddy.runner.scheduling.enabled} (default true; false in tests),
 * see {@link WorkerConfig}.
 *
 * @param id                 runner id written to runs.runner_id; random if unset
 * @param pollIntervalMs     delay between claim attempts
 * @param reaperIntervalMs   delay between reaper passes
 * @param heartbeatTimeoutMs a Run with a runner whose heartbeat is older is failed
 * @param fake               the fake executor (Build 1 only)
 */
@ConfigurationProperties(prefix = "devbuddy.runner")
public record RunnerProperties(
        String id,
        @DefaultValue("2000") long pollIntervalMs,
        @DefaultValue("10000") long reaperIntervalMs,
        @DefaultValue("30000") long heartbeatTimeoutMs,
        @DefaultValue Fake fake) {

    public RunnerProperties {
        if (pollIntervalMs < 1 || reaperIntervalMs < 1 || heartbeatTimeoutMs < 1) {
            throw new IllegalArgumentException("devbuddy.runner intervals must be positive");
        }
        if (fake.enabled() && heartbeatTimeoutMs <= fake.stepMs()) {
            // The fake heartbeats once per step; a shorter timeout would reap it.
            throw new IllegalArgumentException(
                    "devbuddy.runner.heartbeat-timeout-ms must exceed devbuddy.runner.fake.step-ms");
        }
    }

    /**
     * @param enabled       off by default; when off, nothing claims Runs
     * @param stepMs        length of one simulated step
     * @param stepsPerPhase simulated steps per phase (ANALYZING, TESTING, INVESTIGATING)
     */
    public record Fake(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("500") long stepMs,
            @DefaultValue("3") int stepsPerPhase) {

        public Fake {
            if (stepMs < 1 || stepsPerPhase < 1) {
                throw new IllegalArgumentException("devbuddy.runner.fake step values must be positive");
            }
        }
    }
}

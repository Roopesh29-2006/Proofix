package com.proofix.backend.run;

/**
 * Answer to a runner heartbeat. The runner stops when {@code cancelRequested}
 * is true or {@code status} is not the phase it is working on.
 */
public record RunnerHeartbeat(boolean cancelRequested, RunStatus status) {
}

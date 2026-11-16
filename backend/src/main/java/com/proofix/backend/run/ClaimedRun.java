package com.proofix.backend.run;

import java.util.UUID;

/** A Run a worker has claimed: ANALYZING (from QUEUED) or TESTING (approved). */
public record ClaimedRun(UUID runId, String runnerId, RunStatus status) {
}

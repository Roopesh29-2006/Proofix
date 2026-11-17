package com.proofix.backend.run;

import java.util.UUID;

/** A Run claimed by an external runner through the runner API (Build 2A: ANALYZING). */
public record RunnerClaim(UUID runId, String repoUrl, RunStatus status) {
}

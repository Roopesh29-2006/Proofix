package com.proofix.backend.security;

import java.util.UUID;

/**
 * The authenticated caller of /api/runner/**: one runner key acting for its
 * owner, plus the instance label from X-Runner-Instance. The key is the
 * security boundary; the instance id is a correctness label chosen by the
 * runner (not a credential). Nothing here comes from a request body.
 *
 * @param instanceId null until RunnerInstanceFilter has validated the header
 */
public record RunnerPrincipal(UUID keyId, UUID ownerUserId, String instanceId) {

    public RunnerPrincipal withInstance(String instance) {
        return new RunnerPrincipal(keyId, ownerUserId, instance);
    }

    /** runs.runner_id for this key and instance. */
    public String runnerId() {
        if (instanceId == null) {
            throw new IllegalStateException("runner instance not validated");
        }
        return keyId + ":" + instanceId;
    }
}

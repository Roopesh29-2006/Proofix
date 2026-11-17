package com.proofix.backend.security;

import java.util.UUID;

/**
 * The authenticated caller of /api/runner/**: one runner key acting for its
 * owner. Comes only from the key lookup, never from a request body.
 */
public record RunnerPrincipal(UUID keyId, UUID ownerUserId) {

    /** runs.runner_id for this key and a validated instance id. */
    public String runnerId(String instanceId) {
        return keyId + ":" + instanceId;
    }
}

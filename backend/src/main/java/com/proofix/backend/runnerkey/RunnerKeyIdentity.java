package com.proofix.backend.runnerkey;

import java.util.UUID;

/** Who an authenticated runner key acts for. Holds no secret. */
public record RunnerKeyIdentity(UUID keyId, UUID ownerUserId) {
}

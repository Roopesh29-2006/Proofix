package com.proofix.backend.runnerkey;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A runner key as the owner sees it: no plaintext, no hash. */
public record RunnerKeyResponse(
        UUID id,
        String name,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime revokedAt,
        OffsetDateTime expiresAt,
        boolean active) {

    static RunnerKeyResponse from(RunnerKey key, OffsetDateTime now) {
        return new RunnerKeyResponse(key.getId(), key.getName(), key.getCreatedAt(),
                key.getLastUsedAt(), key.getRevokedAt(), key.getExpiresAt(), key.isActive(now));
    }
}

package com.proofix.backend.runnerkey;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The only response that ever carries the plaintext key (201 of
 * {@code POST /api/runner-keys}). {@link #toString()} never shows it.
 */
public record CreatedRunnerKeyResponse(UUID id, String name, String key, OffsetDateTime createdAt) {

    static CreatedRunnerKeyResponse from(CreatedRunnerKey created) {
        RunnerKey key = created.key();
        return new CreatedRunnerKeyResponse(
                key.getId(), key.getName(), created.plaintext(), key.getCreatedAt());
    }

    @Override
    public String toString() {
        return "CreatedRunnerKeyResponse[id=" + id + ", name=" + name + ", key=<redacted>]";
    }
}

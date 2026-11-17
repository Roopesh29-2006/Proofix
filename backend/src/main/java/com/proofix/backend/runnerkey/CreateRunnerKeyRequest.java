package com.proofix.backend.runnerkey;

/** {@code POST /api/runner-keys}. The name is validated in RunnerKeyService. */
public record CreateRunnerKeyRequest(String name) {
}

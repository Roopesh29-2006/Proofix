package com.proofix.backend.intelligence.model;

import java.util.UUID;

public record EvidenceReference(
        String repositoryPath,
        UUID snapshotFileId,
        UUID snapshotId,
        String githubSha,
        String contentHash) {
}

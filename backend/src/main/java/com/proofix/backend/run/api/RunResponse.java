package com.proofix.backend.run.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.proofix.backend.run.Run;

/**
 * Public view of a Run. Deliberately excludes worker internals
 * (runner_id, heartbeat_at) and time accounting.
 */
public record RunResponse(
        UUID id,
        String repoUrl,
        String commitSha,
        String status,
        boolean cancelRequested,
        String failureReason,
        int maxMinutes,
        int maxGeminiCalls,
        int maxUsers,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime finishedAt) {

    public static RunResponse from(Run run) {
        return new RunResponse(
                run.getId(),
                run.getRepoUrl(),
                run.getCommitSha(),
                run.getStatus().name(),
                run.isCancelRequested(),
                run.getFailureReason(),
                run.getMaxMinutes(),
                run.getMaxGeminiCalls(),
                run.getMaxUsers(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getFinishedAt());
    }
}

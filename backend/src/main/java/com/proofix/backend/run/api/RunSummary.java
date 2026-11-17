package com.proofix.backend.run.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.proofix.backend.run.Run;

/**
 * One Run in GET /api/runs: no plan body and no fix hint, only whether a
 * plan exists and the failure code. The full view is GET /api/runs/{id}.
 */
public record RunSummary(
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
        OffsetDateTime finishedAt,
        boolean hasPlan,
        String failureCode) {

    public static RunSummary from(Run run) {
        return new RunSummary(
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
                run.getFinishedAt(),
                run.getPlan() != null,
                run.getFailureCode() == null ? null : run.getFailureCode().name());
    }
}

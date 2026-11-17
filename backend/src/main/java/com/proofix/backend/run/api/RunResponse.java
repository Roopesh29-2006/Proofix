package com.proofix.backend.run.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.proofix.backend.run.FailureCode;
import com.proofix.backend.run.Run;
import com.proofix.backend.run.RunStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The owner's full view of one Run (GET /api/runs/{id} and the action
 * endpoints). Deliberately excludes worker internals (runner ids,
 * heartbeat_at) and time accounting.
 *
 * @param plan    the reported plan, null until a runner reports it
 * @param failure code, reason and the server's fix hint when FAILED, else null
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
        OffsetDateTime finishedAt,
        JsonNode plan,
        Failure failure) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Why a Run failed. The hint comes from {@link FailureCode}, never from a runner. */
    public record Failure(String code, String reason, String fixHint) {

        static Failure of(Run run) {
            if (run.getStatus() != RunStatus.FAILED) {
                return null;
            }
            FailureCode code = run.getFailureCode();
            return new Failure(code == null ? null : code.name(), run.getFailureReason(),
                    code == null ? null : code.fixHint());
        }
    }

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
                run.getFinishedAt(),
                run.getPlan() == null ? null : MAPPER.readTree(run.getPlan()),
                Failure.of(run));
    }
}

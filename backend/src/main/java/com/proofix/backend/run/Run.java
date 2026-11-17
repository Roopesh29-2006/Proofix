package com.proofix.backend.run;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One DevBuddy analysis of one repository.
 *
 * <p>Status has no public setter: {@link #moveTo} is package-private and
 * only {@link RunStateMachine} calls it. Code outside this package can read
 * the status but cannot change it.
 */
@Entity
@Table(name = "runs")
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "repo_url", nullable = false, updatable = false, length = 500)
    private String repoUrl;

    @Column(name = "commit_sha", length = 100)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RunStatus status;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "runner_id", length = 100)
    private String runnerId;

    @Column(name = "heartbeat_at")
    private OffsetDateTime heartbeatAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private FailureCode failureCode;

    /** The runner that claimed this Run last; set on claim, never cleared. */
    @Column(name = "last_runner_id", length = 100)
    private String lastRunnerId;

    /** The validated, canonical plan a runner reported (schema v1); NULL until then. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan")
    private String plan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "load_model")
    private String loadModel;

    @Column(name = "max_minutes", nullable = false, updatable = false)
    private int maxMinutes;

    @Column(name = "max_gemini_calls", nullable = false, updatable = false)
    private int maxGeminiCalls;

    @Column(name = "max_users", nullable = false, updatable = false)
    private int maxUsers;

    @Column(name = "active_ms", nullable = false)
    private long activeMs;

    @Column(name = "phase_started_at")
    private OffsetDateTime phaseStartedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Run() {
        // JPA
    }

    /** A new QUEUED Run. Only {@link RunStateMachine#create} calls this. */
    static Run queued(UUID ownerUserId, String repoUrl, RunBudgets budgets, OffsetDateTime now) {
        Run run = new Run();
        run.ownerUserId = ownerUserId;
        run.repoUrl = repoUrl;
        run.status = RunStatus.QUEUED;
        run.maxMinutes = budgets.maxMinutes();
        run.maxGeminiCalls = budgets.maxGeminiCalls();
        run.maxUsers = budgets.maxUsers();
        run.createdAt = now;
        run.updatedAt = now;
        return run;
    }

    /**
     * Applies an already validated status change and its bookkeeping. Only
     * {@link RunStateMachine#transition} calls this.
     */
    void moveTo(RunStatus to, OffsetDateTime now, String failureReason) {
        // Bank the active stretch that is ending (active -> anything).
        if (status.isActive() && phaseStartedAt != null) {
            activeMs += Math.max(0, Duration.between(phaseStartedAt, now).toMillis());
            phaseStartedAt = null;
        }
        if (to.isActive()) {
            phaseStartedAt = now;
        } else {
            // No worker owns a Run that is waiting, queued or finished.
            runnerId = null;
        }
        if (to.isFinal()) {
            finishedAt = now;
        }
        if (to == RunStatus.FAILED) {
            this.failureReason = failureReason;
        }
        status = to;
        updatedAt = now;
    }

    /** Worker time used so far, including the active stretch in progress. */
    public long activeMillis(Instant now) {
        long running = phaseStartedAt == null
                ? 0
                : Math.max(0, Duration.between(phaseStartedAt.toInstant(), now).toMillis());
        return activeMs + running;
    }

    /** True once worker time is strictly greater than max_minutes. */
    public boolean exceedsMaxMinutes(Instant now) {
        return activeMillis(now) > maxMinutes * 60_000L;
    }

    // ------------------------------------------------------------------
    // Non-status fields. Package-private: only run-package services
    // (RunLifecycle, and the worker steps in Build 1 commit 3) change them.
    // ------------------------------------------------------------------

    void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    void setRunnerId(String runnerId) {
        this.runnerId = runnerId;
    }

    void setHeartbeatAt(OffsetDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    void setLoadModel(String loadModel) {
        this.loadModel = loadModel;
    }

    void setLastRunnerId(String lastRunnerId) {
        this.lastRunnerId = lastRunnerId;
    }

    void setPlan(String plan) {
        this.plan = plan;
    }

    /** Only once the Run is FAILED (runs_failure_code_check). */
    void setFailureCode(FailureCode failureCode) {
        this.failureCode = failureCode;
    }

    void requestCancel(OffsetDateTime now) {
        this.cancelRequested = true;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public RunStatus getStatus() {
        return status;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getRunnerId() {
        return runnerId;
    }

    public OffsetDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getLoadModel() {
        return loadModel;
    }

    public FailureCode getFailureCode() {
        return failureCode;
    }

    public String getLastRunnerId() {
        return lastRunnerId;
    }

    public String getPlan() {
        return plan;
    }

    public int getMaxMinutes() {
        return maxMinutes;
    }

    public int getMaxGeminiCalls() {
        return maxGeminiCalls;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public long getActiveMs() {
        return activeMs;
    }

    public OffsetDateTime getPhaseStartedAt() {
        return phaseStartedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public long getVersion() {
        return version;
    }
}

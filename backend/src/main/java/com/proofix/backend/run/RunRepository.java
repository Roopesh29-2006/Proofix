package com.proofix.backend.run;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RunRepository extends JpaRepository<Run, UUID> {

    /** Row lock (SELECT ... FOR UPDATE) for status and cancel changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Run r WHERE r.id = :id")
    Optional<Run> findByIdForUpdate(@Param("id") UUID id);

    /** Owner-scoped row lock; a Run of another user is simply not found. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Run r WHERE r.id = :id AND r.ownerUserId = :owner")
    Optional<Run> findByIdAndOwnerForUpdate(@Param("id") UUID id, @Param("owner") UUID owner);

    Optional<Run> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    Page<Run> findByOwnerUserId(UUID ownerUserId, Pageable pageable);

    long countByOwnerUserIdAndStatusIn(UUID ownerUserId, Collection<RunStatus> statuses);

    /**
     * Locks the owner's users row for the rest of the transaction, so that
     * cap checks for the same user run one at a time. FOR NO KEY UPDATE
     * conflicts with itself but not with the KEY SHARE locks taken by
     * foreign-key checks, so inserts of other rows that reference the user
     * (projects, ...) are not blocked. Empty if the user does not exist.
     */
    @Query(value = "SELECT 1 FROM users WHERE user_id = :owner FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<Integer> lockOwner(@Param("owner") UUID owner);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM users WHERE user_id = :owner)",
            nativeQuery = true)
    boolean ownerExists(@Param("owner") UUID owner);

    // ------------------------------------------------------------------
    // Worker queries
    // ------------------------------------------------------------------

    /**
     * The oldest Run waiting for a worker (QUEUED, or TESTING after plan
     * approval with no runner), locked; Runs locked by another transaction
     * are skipped, so two workers never get the same Run. Uses the partial
     * index idx_runs_claimable.
     */
    @Query(value = "SELECT id FROM runs "
            + "WHERE runner_id IS NULL AND status IN ('QUEUED', 'TESTING') "
            + "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<UUID> lockNextClaimableId();

    /**
     * Runner API claim (security backlog #10): the oldest QUEUED Run of ONE
     * owner, locked, skipping Runs locked by another transaction. Uses the
     * partial index idx_runs_claimable_owner. Build 2A claims QUEUED only;
     * approved TESTING Runs wait for an endpoint that continues them (2B).
     */
    @Query(value = "SELECT id FROM runs "
            + "WHERE owner_user_id = :owner AND runner_id IS NULL AND status = 'QUEUED' "
            + "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<UUID> lockNextQueuedIdForOwner(@Param("owner") UUID owner);

    /**
     * Liveness only: moves heartbeat_at for a Run this runner owns in an
     * active state. Never touches status, version or updated_at.
     */
    @Modifying
    @Query(value = "UPDATE runs SET heartbeat_at = :now "
            + "WHERE id = :id AND runner_id = :runner "
            + "AND status IN ('ANALYZING', 'TESTING', 'INVESTIGATING')",
            nativeQuery = true)
    int touchHeartbeat(@Param("id") UUID id, @Param("runner") String runner,
            @Param("now") OffsetDateTime now);

    /** Reaper candidates: active, owned by a runner, heartbeat older than cutoff. */
    @Query(value = "SELECT id FROM runs "
            + "WHERE status IN ('ANALYZING', 'TESTING', 'INVESTIGATING') "
            + "AND runner_id IS NOT NULL "
            + "AND (heartbeat_at IS NULL OR heartbeat_at < :cutoff) "
            + "ORDER BY heartbeat_at NULLS FIRST",
            nativeQuery = true)
    List<UUID> findStaleHeartbeatIds(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Reaper candidates: active (with or without a runner) and worker time
     * strictly above max_minutes. Same rule as Run.exceedsMaxMinutes.
     */
    @Query(value = "SELECT id FROM runs "
            + "WHERE status IN ('ANALYZING', 'TESTING', 'INVESTIGATING') "
            + "AND active_ms + EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - phase_started_at)) "
            + "* 1000 > max_minutes * 60000.0",
            nativeQuery = true)
    List<UUID> findOverBudgetIds(@Param("now") OffsetDateTime now);
}

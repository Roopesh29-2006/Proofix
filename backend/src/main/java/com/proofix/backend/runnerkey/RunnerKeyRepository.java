package com.proofix.backend.runnerkey;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RunnerKeyRepository extends JpaRepository<RunnerKey, UUID> {

    Optional<RunnerKey> findByKeyHash(String keyHash);

    List<RunnerKey> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM RunnerKey k WHERE k.id = :id AND k.ownerUserId = :owner")
    Optional<RunnerKey> findByIdAndOwnerForUpdate(@Param("id") UUID id, @Param("owner") UUID owner);

    @Query("SELECT count(k) FROM RunnerKey k WHERE k.ownerUserId = :owner "
            + "AND k.revokedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt > :now)")
    long countActive(@Param("owner") UUID owner, @Param("now") OffsetDateTime now);

    /**
     * Serialises cap checks for one user (same lock as the Run caps:
     * FOR NO KEY UPDATE on the users row). Empty if the user does not exist.
     */
    @Query(value = "SELECT 1 FROM users WHERE user_id = :owner FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<Integer> lockOwner(@Param("owner") UUID owner);

    /**
     * Sets last_used_at only if it is unset or at least a minute old
     * ({@code cutoff} = now - 1 minute), so a busy runner writes at most once
     * a minute.
     */
    @Modifying
    @Query(value = "UPDATE runner_keys SET last_used_at = :now WHERE id = :id "
            + "AND (last_used_at IS NULL OR last_used_at <= :cutoff)",
            nativeQuery = true)
    int touchLastUsed(@Param("id") UUID id, @Param("now") OffsetDateTime now,
            @Param("cutoff") OffsetDateTime cutoff);
}

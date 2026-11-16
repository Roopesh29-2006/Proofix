package com.proofix.backend.run;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * cap checks for the same user run one at a time. Empty if the user
     * does not exist.
     */
    @Query(value = "SELECT 1 FROM users WHERE user_id = :owner FOR UPDATE", nativeQuery = true)
    Optional<Integer> lockOwner(@Param("owner") UUID owner);
}

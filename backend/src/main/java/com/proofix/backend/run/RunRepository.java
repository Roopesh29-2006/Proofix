package com.proofix.backend.run;

import java.util.Optional;
import java.util.UUID;

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
}

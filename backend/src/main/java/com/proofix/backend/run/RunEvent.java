package com.proofix.backend.run;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One status change of a Run (append-only audit trail). */
@Entity
@Table(name = "run_events")
public class RunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 30)
    private RunStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private RunStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, updatable = false, length = 10)
    private RunActor actor;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RunEvent() {
        // JPA
    }

    RunEvent(UUID runId, RunStatus fromStatus, RunStatus toStatus,
            RunActor actor, String note, OffsetDateTime createdAt) {
        this.runId = runId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public RunStatus getFromStatus() {
        return fromStatus;
    }

    public RunStatus getToStatus() {
        return toStatus;
    }

    public RunActor getActor() {
        return actor;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

package com.proofix.backend.run;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.proofix.backend.exception.ResourceNotFoundException;

/**
 * The only code path that changes a Run's status.
 *
 * <p>{@link #transition} locks the row, validates the move against
 * {@link RunTransitions}, applies it and writes a {@link RunEvent}, all in
 * one transaction. {@link #create} sets the initial QUEUED status and writes
 * the creation event (from_status NULL).
 */
@Service
public class RunStateMachine {

    private final RunRepository runRepository;
    private final RunEventRepository eventRepository;
    private final Clock clock;

    public RunStateMachine(
            RunRepository runRepository,
            RunEventRepository eventRepository,
            Clock clock) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Transactional
    public Run create(UUID ownerUserId, String repoUrl, RunBudgets budgets) {
        if (ownerUserId == null || repoUrl == null || repoUrl.isBlank() || budgets == null) {
            throw new IllegalArgumentException("Owner, repository URL and budgets are required");
        }
        OffsetDateTime now = now();
        Run run = runRepository.save(Run.queued(ownerUserId, repoUrl, budgets, now));
        eventRepository.save(new RunEvent(
                run.getId(), null, RunStatus.QUEUED, RunActor.USER, "created", now));
        return run;
    }

    /**
     * Moves a Run to {@code to}.
     *
     * @param note free text for the event; required (and stored as
     *             failure_reason) when {@code to} is FAILED
     * @throws IllegalRunTransitionException if the table forbids the move
     * @throws ResourceNotFoundException if the Run does not exist
     */
    @Transactional
    public Run transition(UUID runId, RunStatus to, RunActor actor, String note) {
        if (to == null || actor == null) {
            throw new IllegalArgumentException("Target status and actor are required");
        }
        if (to == RunStatus.FAILED && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("A failure reason is required");
        }

        Run run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Run not found"));

        RunStatus from = run.getStatus();
        if (!RunTransitions.isAllowed(from, to)) {
            throw new IllegalRunTransitionException(from, to);
        }

        OffsetDateTime now = now();
        run.moveTo(to, now, note);
        Run saved = runRepository.save(run);
        eventRepository.save(new RunEvent(saved.getId(), from, to, actor, note, now));
        return saved;
    }

    private OffsetDateTime now() {
        return RunTime.now(clock);
    }
}

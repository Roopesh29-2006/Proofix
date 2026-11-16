package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * V2 schema, entity mapping and constraints against proofix_test. Each test
 * runs in a transaction that is rolled back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RunPersistenceTest {

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (first_name, last_name, email) "
                        + "VALUES ('Run', 'Test', ?) RETURNING user_id",
                UUID.class,
                "run-" + UUID.randomUUID() + "@example.test");
    }

    private Run createRun() {
        return machine.create(userId, "https://github.com/octo/demo",
                new RunBudgets(30, 50, 100));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ------------------------------------------------------------------
    // Mapping and events
    // ------------------------------------------------------------------

    @Test
    void transitionsPersistStatusEventsAndVersion() {
        UUID runId = createRun().getId();
        flushAndClear();

        machine.transition(runId, RunStatus.ANALYZING, RunActor.WORKER, "claimed");
        flushAndClear();
        machine.transition(runId, RunStatus.AWAITING_PLAN_OK, RunActor.WORKER, null);
        flushAndClear();

        Run run = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.AWAITING_PLAN_OK, run.getStatus());
        assertEquals(2, run.getVersion());
        assertFalse(run.isCancelRequested());
        assertNull(run.getPhaseStartedAt());
        assertNull(run.getFinishedAt());

        List<RunEvent> events = eventRepository.findByRunIdOrderByIdAsc(runId);
        assertEquals(3, events.size());
        assertNull(events.get(0).getFromStatus());
        assertEquals(RunStatus.QUEUED, events.get(0).getToStatus());
        assertEquals(RunStatus.QUEUED, events.get(1).getFromStatus());
        assertEquals(RunStatus.ANALYZING, events.get(1).getToStatus());
        assertEquals("claimed", events.get(1).getNote());
        assertEquals(RunActor.WORKER, events.get(1).getActor());
        assertEquals(RunStatus.AWAITING_PLAN_OK, events.get(2).getToStatus());
    }

    @Test
    void finalTransitionPersistsFinishedAtAndFailureReason() {
        UUID runId = createRun().getId();
        machine.transition(runId, RunStatus.FAILED, RunActor.SYSTEM, "worker heartbeat lost");
        flushAndClear();

        Run run = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals("worker heartbeat lost", run.getFailureReason());
        assertTrue(run.getFinishedAt() != null);
    }

    @Test
    void illegalTransitionLeavesTheDatabaseUnchanged() {
        UUID runId = createRun().getId();
        flushAndClear();

        assertThrows(IllegalRunTransitionException.class,
                () -> machine.transition(runId, RunStatus.DONE, RunActor.WORKER, null));
        flushAndClear();

        assertEquals(RunStatus.QUEUED, runRepository.findById(runId).orElseThrow().getStatus());
        assertEquals(1, eventRepository.findByRunIdOrderByIdAsc(runId).size());
    }

    @Test
    void loadModelRoundTripsAsJsonb() {
        Run run = createRun();
        run.setLoadModel("{\"journeys\": 2}");
        runRepository.save(run);
        flushAndClear();

        String stored = jdbc.queryForObject(
                "SELECT load_model->>'journeys' FROM runs WHERE id = ?",
                String.class, run.getId());
        assertEquals("2", stored);
        assertTrue(runRepository.findById(run.getId()).orElseThrow()
                .getLoadModel().contains("journeys"));
    }

    // ------------------------------------------------------------------
    // CHECK constraints and foreign keys
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(RunStatus.class)
    void everyStatusIsAcceptedByTheDatabase(RunStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertRaw(userId, status.name(),
                status.isActive() ? now : null,
                status.isFinal() ? now : null,
                30);
    }

    @Test
    void unknownStatusIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(userId, "RUNNING", null, null, 30));
    }

    @Test
    void finishedAtWithoutFinalStatusIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(userId, "QUEUED", null, OffsetDateTime.now(), 30));
    }

    @Test
    void finalStatusWithoutFinishedAtIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(userId, "DONE", null, null, 30));
    }

    @Test
    void activeStatusWithoutPhaseStartIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(userId, "TESTING", null, null, 30));
    }

    @Test
    void phaseStartOutsideActiveStatusIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(userId, "AWAITING_PLAN_OK", OffsetDateTime.now(), null, 30));
    }

    @Test
    void nonPositiveBudgetIsRejected() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(userId, "QUEUED", null, null, 0));
    }

    @Test
    void ownerMustExist() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRaw(UUID.randomUUID(), "QUEUED", null, null, 30));
    }

    @Test
    void unknownActorIsRejected() {
        UUID runId = createRun().getId();
        entityManager.flush();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO run_events (run_id, to_status, actor) VALUES (?, 'QUEUED', 'ROBOT')",
                runId));
    }

    private void insertRaw(
            UUID owner, String status, OffsetDateTime phaseStartedAt,
            OffsetDateTime finishedAt, int maxMinutes) {
        jdbc.update(
                "INSERT INTO runs (id, owner_user_id, repo_url, status, max_minutes, "
                        + "max_gemini_calls, max_users, phase_started_at, finished_at) "
                        + "VALUES (gen_random_uuid(), ?, 'https://github.com/o/r', ?, ?, "
                        + "10, 10, ?, ?)",
                owner, status, maxMinutes, phaseStartedAt, finishedAt);
    }
}

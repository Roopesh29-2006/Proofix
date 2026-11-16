package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.proofix.backend.run.RunTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/** The locked worker steps: heartbeat, checkpoint, advance, fail. */
@SpringBootTest
@ActiveProfiles("test")
class RunWorkerOpsTest {

    private static final String RUNNER = "runner-1";

    @Autowired
    private RunWorkerOps ops;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunLifecycle lifecycle;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    private RunTestUsers users;
    private RunFixtures fixtures;
    private TestUser owner;

    @BeforeEach
    void setUp() {
        users = new RunTestUsers(jdbc, jwtService);
        users.purgeAllRuns();
        fixtures = new RunFixtures(machine, ops, lifecycle, runRepository);
        owner = users.create();
    }

    @AfterEach
    void tearDown() {
        users.purgeAllRuns();
        users.deleteAll();
    }

    private RunEvent lastEvent(Run run) {
        List<RunEvent> events = eventRepository.findByRunIdOrderByIdAsc(run.getId());
        return events.get(events.size() - 1);
    }

    // ------------------------------------------------------------------
    // Heartbeat: liveness only, never status
    // ------------------------------------------------------------------

    @Test
    void heartbeatMovesOnlyHeartbeatAt() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        jdbc.update("UPDATE runs SET heartbeat_at = now() - interval '5 minutes' WHERE id = ?",
                claim.runId());
        Run before = fixtures.reload(claim.runId());
        int eventsBefore = eventRepository.findByRunIdOrderByIdAsc(claim.runId()).size();

        assertTrue(ops.heartbeat(claim.runId(), RUNNER));

        Run after = fixtures.reload(claim.runId());
        assertTrue(after.getHeartbeatAt().isAfter(before.getHeartbeatAt()));
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getVersion(), after.getVersion());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        assertEquals(before.getPhaseStartedAt(), after.getPhaseStartedAt());
        assertEquals(before.getActiveMs(), after.getActiveMs());
        assertEquals(eventsBefore, eventRepository.findByRunIdOrderByIdAsc(claim.runId()).size());
    }

    @Test
    void heartbeatFromAnotherRunnerIsRefused() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        OffsetDateTime before = fixtures.reload(claim.runId()).getHeartbeatAt();

        assertFalse(ops.heartbeat(claim.runId(), "someone-else"));
        assertEquals(before, fixtures.reload(claim.runId()).getHeartbeatAt());
    }

    @Test
    void heartbeatAfterTheRunLeftActiveWorkIsRefused() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        machine.transition(claim.runId(), RunStatus.FAILED, RunActor.SYSTEM,
                "worker heartbeat lost");

        assertFalse(ops.heartbeat(claim.runId(), RUNNER));
    }

    // ------------------------------------------------------------------
    // Checkpoint: the cancel check between steps
    // ------------------------------------------------------------------

    @Test
    void checkpointContinuesWhenNothingIsPending() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertEquals(StepOutcome.CONTINUE, ops.checkpoint(claim.runId(), RUNNER));
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"ANALYZING", "TESTING", "INVESTIGATING"})
    void checkpointHonoursARequestedCancel(RunStatus status) {
        ClaimedRun claim = fixtures.claimedIn(status, owner.id(), RUNNER);
        Run flagged = lifecycle.cancel(owner.id(), claim.runId());
        assertTrue(flagged.isCancelRequested());
        assertEquals(status, flagged.getStatus());

        assertEquals(StepOutcome.CANCELLED, ops.checkpoint(claim.runId(), RUNNER));

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.CANCELLED, stored.getStatus());
        assertNull(stored.getRunnerId());
        RunEvent event = lastEvent(stored);
        assertEquals(status, event.getFromStatus());
        assertEquals(RunActor.WORKER, event.getActor());
        assertEquals("cancel requested", event.getNote());
    }

    @Test
    void checkpointByAnotherRunnerIsLost() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertEquals(StepOutcome.LOST, ops.checkpoint(claim.runId(), "someone-else"));
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void checkpointAfterTheReaperFailedTheRunIsLost() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        machine.transition(claim.runId(), RunStatus.FAILED, RunActor.SYSTEM,
                "worker heartbeat lost");

        assertEquals(StepOutcome.LOST, ops.checkpoint(claim.runId(), RUNNER));
        assertEquals(RunStatus.FAILED, fixtures.reload(claim.runId()).getStatus());
    }

    // ------------------------------------------------------------------
    // Advance, including cancel-vs-AWAITING inside the locked step
    // ------------------------------------------------------------------

    @Test
    void advanceMovesTheRunAndRecordsAWorkerEvent() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertEquals(StepOutcome.MOVED, ops.advance(
                claim.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK));

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.AWAITING_PLAN_OK, stored.getStatus());
        assertNull(stored.getRunnerId());
        assertEquals(RunActor.WORKER, lastEvent(stored).getActor());
    }

    @Test
    void cancelRequestedBeforeAwaitingPlanCancelsInstead() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        lifecycle.cancel(owner.id(), claim.runId());

        assertEquals(StepOutcome.CANCELLED, ops.advance(
                claim.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK));

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.CANCELLED, stored.getStatus());
        RunEvent event = lastEvent(stored);
        assertEquals(RunStatus.ANALYZING, event.getFromStatus());
        assertEquals(RunStatus.CANCELLED, event.getToStatus());
        assertEquals(RunActor.WORKER, event.getActor());
        assertEquals("cancel requested", event.getNote());
        assertTrue(eventRepository.findByRunIdOrderByIdAsc(claim.runId()).stream()
                .noneMatch(e -> e.getToStatus() == RunStatus.AWAITING_PLAN_OK));
    }

    @Test
    void cancelRequestedBeforeAwaitingFixCancelsInstead() {
        ClaimedRun claim = fixtures.claimedInvestigating(owner.id(), RUNNER);
        lifecycle.cancel(owner.id(), claim.runId());

        assertEquals(StepOutcome.CANCELLED, ops.advance(
                claim.runId(), RUNNER, RunStatus.INVESTIGATING, RunStatus.AWAITING_FIX_OK));

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.CANCELLED, stored.getStatus());
        assertEquals("cancel requested", lastEvent(stored).getNote());
        assertTrue(eventRepository.findByRunIdOrderByIdAsc(claim.runId()).stream()
                .noneMatch(e -> e.getToStatus() == RunStatus.AWAITING_FIX_OK));
    }

    @Test
    void cancelRequestedBeforeAnActiveToActiveStepAlsoCancels() {
        ClaimedRun claim = fixtures.claimedTesting(owner.id(), RUNNER);
        lifecycle.cancel(owner.id(), claim.runId());

        assertEquals(StepOutcome.CANCELLED, ops.advance(
                claim.runId(), RUNNER, RunStatus.TESTING, RunStatus.INVESTIGATING));
        assertEquals(RunStatus.CANCELLED, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void advanceFromAnUnexpectedStatusIsLostAndChangesNothing() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        machine.transition(claim.runId(), RunStatus.FAILED, RunActor.SYSTEM, "max_minutes exceeded");
        int eventsBefore = eventRepository.findByRunIdOrderByIdAsc(claim.runId()).size();

        assertEquals(StepOutcome.LOST, ops.advance(
                claim.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK));

        assertEquals(RunStatus.FAILED, fixtures.reload(claim.runId()).getStatus());
        assertEquals(eventsBefore, eventRepository.findByRunIdOrderByIdAsc(claim.runId()).size());
    }

    @Test
    void advanceByAnotherRunnerIsLost() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertEquals(StepOutcome.LOST, ops.advance(
                claim.runId(), "someone-else", RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK));
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    // ------------------------------------------------------------------
    // Fail
    // ------------------------------------------------------------------

    @Test
    void failMarksTheRunFailedWithTheReason() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertEquals(StepOutcome.MOVED, ops.fail(claim.runId(), RUNNER, "worker error"));

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.FAILED, stored.getStatus());
        assertEquals("worker error", stored.getFailureReason());
        assertNotNull(stored.getFinishedAt());
        assertEquals(RunActor.WORKER, lastEvent(stored).getActor());
    }

    @Test
    void failByAnotherRunnerIsLost() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertEquals(StepOutcome.LOST, ops.fail(claim.runId(), "someone-else", "worker error"));
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }
}

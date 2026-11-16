package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
import com.proofix.backend.run.worker.RunReaper;
import com.proofix.backend.security.JwtService;

/**
 * Reaper: stale heartbeat (only Runs with a runner) and max_minutes (all
 * active Runs, including approved-but-unclaimed). Both the candidate queries
 * and the locked re-checks are tested, so a mutation in either layer fails.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunReaperTest {

    private static final String RUNNER = "runner-1";

    @Autowired
    private RunReaper reaper;

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
    private Clock clock;

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

    // Test fixtures move non-status timestamps into the past; status itself is
    // only ever changed through the state machine.
    private void heartbeatMinutesAgo(UUID runId, int minutes) {
        jdbc.update("UPDATE runs SET heartbeat_at = now() - make_interval(mins => ?) WHERE id = ?",
                minutes, runId);
    }

    private void phaseStartedMinutesAgo(UUID runId, int minutes) {
        jdbc.update("UPDATE runs SET phase_started_at = now() - make_interval(mins => ?) "
                + "WHERE id = ?", minutes, runId);
    }

    private OffsetDateTime cutoff() {
        // Same rule as the reaper: heartbeat older than the timeout (30 s).
        return OffsetDateTime.now(clock).minusSeconds(30);
    }

    private RunEvent lastEvent(UUID runId) {
        List<RunEvent> events = eventRepository.findByRunIdOrderByIdAsc(runId);
        return events.get(events.size() - 1);
    }

    // ------------------------------------------------------------------
    // Stale heartbeat
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"ANALYZING", "TESTING", "INVESTIGATING"})
    void staleHeartbeatWithARunnerFailsTheRun(RunStatus status) {
        ClaimedRun claim = fixtures.claimedIn(status, owner.id(), RUNNER);
        heartbeatMinutesAgo(claim.runId(), 5);

        RunReaper.ReapResult result = reaper.reapOnce();

        assertEquals(1, result.heartbeatLost());
        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.FAILED, stored.getStatus());
        assertEquals("worker heartbeat lost", stored.getFailureReason());
        assertNotNull(stored.getFinishedAt());
        assertNull(stored.getRunnerId());
        RunEvent event = lastEvent(claim.runId());
        assertEquals(status, event.getFromStatus());
        assertEquals(RunActor.SYSTEM, event.getActor());
    }

    @Test
    void freshHeartbeatIsLeftAlone() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        RunReaper.ReapResult result = reaper.reapOnce();

        assertEquals(0, result.heartbeatLost());
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void staleHeartbeatWithoutARunnerIsNotHeartbeatLoss() {
        Run run = fixtures.unclaimedTesting(owner.id(), RUNNER);
        heartbeatMinutesAgo(run.getId(), 5);

        assertEquals(0, reaper.reapOnce().heartbeatLost());
        assertEquals(RunStatus.TESTING, fixtures.reload(run.getId()).getStatus());
    }

    @Test
    void staleHeartbeatQuerySelectsOnlyActiveRunsWithARunner() {
        ClaimedRun owned = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        heartbeatMinutesAgo(owned.runId(), 5);
        ClaimedRun parked = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        ops.advance(parked.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK);
        heartbeatMinutesAgo(parked.runId(), 5);
        // Last: an unclaimed TESTING Run is itself claimable.
        Run unclaimed = fixtures.unclaimedTesting(owner.id(), RUNNER);
        heartbeatMinutesAgo(unclaimed.getId(), 5);

        assertEquals(List.of(owned.runId()), runRepository.findStaleHeartbeatIds(cutoff()));
    }

    @Test
    void lockedRecheckRefusesARunWithoutARunner() {
        Run run = fixtures.unclaimedTesting(owner.id(), RUNNER);
        heartbeatMinutesAgo(run.getId(), 5);

        assertFalse(ops.reapStaleHeartbeat(run.getId(), cutoff()));
        assertEquals(RunStatus.TESTING, fixtures.reload(run.getId()).getStatus());
    }

    @Test
    void lockedRecheckRefusesAFreshHeartbeat() {
        // The Run was selected, then heartbeated before the reaper locked it.
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertFalse(ops.reapStaleHeartbeat(claim.runId(), cutoff()));
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void awaitingAndFinalRunsAreNeverReaped() {
        ClaimedRun parked = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        ops.advance(parked.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK);
        heartbeatMinutesAgo(parked.runId(), 600);
        ClaimedRun done = fixtures.claimedInvestigating(owner.id(), RUNNER);
        ops.advance(done.runId(), RUNNER, RunStatus.INVESTIGATING, RunStatus.DONE);
        heartbeatMinutesAgo(done.runId(), 600);

        RunReaper.ReapResult result = reaper.reapOnce();

        assertEquals(0, result.heartbeatLost() + result.overBudget());
        assertEquals(RunStatus.AWAITING_PLAN_OK, fixtures.reload(parked.runId()).getStatus());
        assertEquals(RunStatus.DONE, fixtures.reload(done.runId()).getStatus());
    }

    // ------------------------------------------------------------------
    // max_minutes (30 in the fixtures)
    // ------------------------------------------------------------------

    @Test
    void overBudgetRunWithARunnerFails() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        phaseStartedMinutesAgo(claim.runId(), 31);

        RunReaper.ReapResult result = reaper.reapOnce();

        assertEquals(1, result.overBudget());
        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.FAILED, stored.getStatus());
        assertEquals("max_minutes exceeded", stored.getFailureReason());
        assertEquals(RunActor.SYSTEM, lastEvent(claim.runId()).getActor());
    }

    @Test
    void overBudgetApprovedButUnclaimedRunFails() {
        Run run = fixtures.unclaimedTesting(owner.id(), RUNNER);
        phaseStartedMinutesAgo(run.getId(), 31);

        assertEquals(1, reaper.reapOnce().overBudget());

        Run stored = fixtures.reload(run.getId());
        assertEquals(RunStatus.FAILED, stored.getStatus());
        assertEquals("max_minutes exceeded", stored.getFailureReason());
    }

    @Test
    void bankedActiveTimeCountsTowardTheBudget() {
        // 29 minutes banked from earlier phases + 2 minutes in this one = 31.
        ClaimedRun claim = fixtures.claimedTesting(owner.id(), RUNNER);
        jdbc.update("UPDATE runs SET active_ms = ? WHERE id = ?", 29 * 60_000L, claim.runId());
        phaseStartedMinutesAgo(claim.runId(), 2);

        assertEquals(1, reaper.reapOnce().overBudget());
        assertEquals(RunStatus.FAILED, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void withinBudgetIsLeftAlone() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        jdbc.update("UPDATE runs SET active_ms = ? WHERE id = ?", 20 * 60_000L, claim.runId());
        phaseStartedMinutesAgo(claim.runId(), 9);

        assertEquals(0, reaper.reapOnce().overBudget());
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void timeSpentAwaitingNeverExhaustsTheBudget() {
        // Banked 29 minutes, then parked for a long time: not reaped.
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        ops.advance(claim.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK);
        jdbc.update("UPDATE runs SET active_ms = ?, updated_at = now() - interval '3 days' "
                + "WHERE id = ?", 29 * 60_000L, claim.runId());

        assertEquals(0, reaper.reapOnce().overBudget());
        assertEquals(RunStatus.AWAITING_PLAN_OK, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void overBudgetQuerySelectsActiveRunsWithAndWithoutARunner() {
        ClaimedRun owned = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        phaseStartedMinutesAgo(owned.runId(), 31);
        ClaimedRun within = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        phaseStartedMinutesAgo(within.runId(), 29);
        // Last: an unclaimed TESTING Run is itself claimable.
        Run unclaimed = fixtures.unclaimedTesting(owner.id(), RUNNER);
        phaseStartedMinutesAgo(unclaimed.getId(), 31);

        List<UUID> ids = runRepository.findOverBudgetIds(OffsetDateTime.now(clock));

        assertEquals(2, ids.size());
        assertTrue(ids.contains(owned.runId()));
        assertTrue(ids.contains(unclaimed.getId()));
    }

    @Test
    void lockedRecheckRefusesARunWithinBudget() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        assertFalse(ops.reapOverBudget(claim.runId(), Instant.now(clock)));
        assertEquals(RunStatus.ANALYZING, fixtures.reload(claim.runId()).getStatus());
    }

    @Test
    void lockedRecheckRefusesARunThatIsNoLongerActive() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        jdbc.update("UPDATE runs SET active_ms = ? WHERE id = ?", 31 * 60_000L, claim.runId());
        ops.advance(claim.runId(), RUNNER, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK);

        assertFalse(ops.reapOverBudget(claim.runId(),
                Instant.now(clock).atOffset(ZoneOffset.UTC).toInstant()));
        assertEquals(RunStatus.AWAITING_PLAN_OK, fixtures.reload(claim.runId()).getStatus());
    }
}

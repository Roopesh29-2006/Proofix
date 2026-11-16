package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.proofix.backend.run.RunTestUsers.TestUser;
import com.proofix.backend.run.worker.FakeRunExecutor;
import com.proofix.backend.run.worker.RunnerProperties;
import com.proofix.backend.security.JwtService;

/**
 * The fake worker end to end against proofix_test, with short steps. It is
 * constructed directly: the bean is off in the test profile.
 */
@SpringBootTest
@ActiveProfiles("test")
class FakeRunExecutorTest {

    private static final String RUNNER = "fake-test";
    private static final long STEP_MS = 150;
    private static final int STEPS = 3;

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
    private FakeRunExecutor executor;
    private ExecutorService threads;

    @BeforeEach
    void setUp() {
        users = new RunTestUsers(jdbc, jwtService);
        users.purgeAllRuns();
        fixtures = new RunFixtures(machine, ops, lifecycle, runRepository);
        owner = users.create();
        executor = new FakeRunExecutor(ops, new RunnerProperties(
                null, 2_000, 10_000, 30_000, new RunnerProperties.Fake(true, STEP_MS, STEPS)));
        threads = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        threads.shutdownNow();
        threads.awaitTermination(10, TimeUnit.SECONDS);
        users.purgeAllRuns();
        users.deleteAll();
    }

    private List<RunStatus> statusHistory(UUID runId) {
        return eventRepository.findByRunIdOrderByIdAsc(runId).stream()
                .map(RunEvent::getToStatus).toList();
    }

    private void waitUntil(UUID runId, Predicate<Run> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.test(fixtures.reload(runId))) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void analyzesThenStopsAtThePlanApproval() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        OffsetDateTime claimedHeartbeat = fixtures.reload(claim.runId()).getHeartbeatAt();

        executor.execute(claim);

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.AWAITING_PLAN_OK, stored.getStatus());
        assertNull(stored.getRunnerId());
        assertTrue(stored.getHeartbeatAt().isAfter(claimedHeartbeat), "no heartbeat while working");
        assertTrue(stored.getActiveMs() >= STEP_MS * STEPS);
        assertEquals(List.of(RunStatus.QUEUED, RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK),
                statusHistory(claim.runId()));
    }

    @Test
    void afterApprovalTestsAndInvestigatesThenStopsAtTheFixApproval() {
        ClaimedRun claim = fixtures.claimedTesting(owner.id(), RUNNER);

        executor.execute(claim);

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.AWAITING_FIX_OK, stored.getStatus());
        assertNull(stored.getRunnerId());
        List<RunStatus> history = statusHistory(claim.runId());
        assertEquals(List.of(RunStatus.TESTING, RunStatus.INVESTIGATING, RunStatus.AWAITING_FIX_OK),
                history.subList(history.size() - 3, history.size()));
    }

    @Test
    void cancelRequestedWhileWorkingStopsAtTheNextCheck() throws Exception {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);

        Future<?> running = threads.submit(() -> executor.execute(claim));
        Run flagged = lifecycle.cancel(owner.id(), claim.runId());
        assertTrue(flagged.isCancelRequested());
        running.get(10, TimeUnit.SECONDS);

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.CANCELLED, stored.getStatus());
        List<RunEvent> events = eventRepository.findByRunIdOrderByIdAsc(claim.runId());
        RunEvent last = events.get(events.size() - 1);
        assertEquals(RunActor.WORKER, last.getActor());
        assertEquals("cancel requested", last.getNote());
        assertTrue(statusHistory(claim.runId()).stream()
                .noneMatch(s -> s == RunStatus.AWAITING_PLAN_OK));
    }

    @Test
    void stopsQuietlyWhenTheRunIsReapedUnderIt() throws Exception {
        ClaimedRun claim = fixtures.claimedTesting(owner.id(), RUNNER);

        Future<?> running = threads.submit(() -> executor.execute(claim));
        machine.transition(claim.runId(), RunStatus.FAILED, RunActor.SYSTEM,
                "worker heartbeat lost");
        running.get(10, TimeUnit.SECONDS);

        Run stored = fixtures.reload(claim.runId());
        assertEquals(RunStatus.FAILED, stored.getStatus());
        assertEquals("worker heartbeat lost", stored.getFailureReason());
        List<RunStatus> history = statusHistory(claim.runId());
        assertEquals(RunStatus.FAILED, history.get(history.size() - 1));
    }

    @Test
    void heartbeatKeepsMovingDuringALongPhase() throws Exception {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), RUNNER);
        OffsetDateTime claimed = fixtures.reload(claim.runId()).getHeartbeatAt();

        Future<?> running = threads.submit(() -> executor.execute(claim));
        waitUntil(claim.runId(), r -> r.getHeartbeatAt().isAfter(claimed)
                && r.getStatus() == RunStatus.ANALYZING);
        running.get(10, TimeUnit.SECONDS);
    }
}

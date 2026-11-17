package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.proofix.backend.run.RunLifecycle;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunStatus;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * A user's cancel racing the runner's report. Whatever the order, the Run
 * ends CANCELLED and is never left AWAITING_PLAN_OK with a pending cancel;
 * the plan is stored only if the report won the lock.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunnerReportCancelRaceTest {

    @Autowired
    private RunWorkerOps ops;

    @Autowired
    private RunLifecycle lifecycle;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunnerKeyService keyService;

    @Autowired
    private RunPlanParser parser;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    private RunnerFixtures fixtures;
    private ExecutorService executor;
    private TestUser alice;
    private CreatedRunnerKey key;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        executor = Executors.newFixedThreadPool(2);
        alice = fixtures.user();
        key = fixtures.key(alice);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        fixtures.deleteAll();
    }

    @RepeatedTest(10)
    void cancelRacingAReportAlwaysEndsCancelled() throws Exception {
        UUID runId = fixtures.claimed(alice, key, "box-1");
        String plan = parser.parse(VALID_REPORT.getBytes(StandardCharsets.UTF_8)).json();
        CountDownLatch start = new CountDownLatch(1);

        Future<RunStatus> report = executor.submit(() -> {
            start.await();
            return ops.reportPlan(alice.id(), runId, runnerId(key, "box-1"), plan);
        });
        Future<?> cancel = executor.submit(() -> {
            start.await();
            return lifecycle.cancel(alice.id(), runId);
        });
        start.countDown();

        RunStatus reported = report.get(20, TimeUnit.SECONDS);
        cancel.get(20, TimeUnit.SECONDS);

        assertEquals("CANCELLED", fixtures.status(runId));
        Long awaiting = jdbc.queryForObject("SELECT count(*) FROM run_events "
                + "WHERE run_id = ? AND to_status = 'AWAITING_PLAN_OK'", Long.class, runId);
        if (reported == RunStatus.AWAITING_PLAN_OK) {
            // Report won the lock; the cancel then cancelled the waiting Run.
            assertNotNull(fixtures.plan(runId));
            assertEquals(1L, awaiting);
        } else {
            // Cancel won: the report saw cancel_requested and cancelled instead.
            assertEquals(RunStatus.CANCELLED, reported);
            assertNull(fixtures.plan(runId));
            assertEquals(0L, awaiting);
        }
    }
}

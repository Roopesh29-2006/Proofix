package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.proofix.backend.run.RunTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * Worker claim: SELECT ... FOR UPDATE SKIP LOCKED over QUEUED Runs and
 * approved TESTING Runs with no runner. Committed data in proofix_test.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunClaimTest {

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    private RunTestUsers users;
    private RunFixtures fixtures;
    private TestUser owner;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        users = new RunTestUsers(jdbc, jwtService);
        users.purgeAllRuns();
        fixtures = new RunFixtures(machine, ops, lifecycle, runRepository);
        owner = users.create();
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        users.purgeAllRuns();
        users.deleteAll();
    }

    // ------------------------------------------------------------------
    // What gets claimed
    // ------------------------------------------------------------------

    @Test
    void claimsTheOldestQueuedRunAndMovesItToAnalyzing() {
        Run first = fixtures.queued(owner.id());
        fixtures.queued(owner.id());

        ClaimedRun claim = ops.claimNext("runner-1").orElseThrow();

        assertEquals(first.getId(), claim.runId());
        assertEquals(RunStatus.ANALYZING, claim.status());
        assertEquals("runner-1", claim.runnerId());

        Run stored = fixtures.reload(first.getId());
        assertEquals(RunStatus.ANALYZING, stored.getStatus());
        assertEquals("runner-1", stored.getRunnerId());
        assertNotNull(stored.getHeartbeatAt());
        assertNotNull(stored.getPhaseStartedAt());

        List<RunEvent> events = eventRepository.findByRunIdOrderByIdAsc(first.getId());
        RunEvent claimed = events.get(events.size() - 1);
        assertEquals(RunStatus.QUEUED, claimed.getFromStatus());
        assertEquals(RunStatus.ANALYZING, claimed.getToStatus());
        assertEquals(RunActor.WORKER, claimed.getActor());
    }

    @Test
    void claimsAnApprovedTestingRunWithoutChangingItsStatus() {
        Run run = fixtures.unclaimedTesting(owner.id(), "runner-1");
        int eventsBefore = eventRepository.findByRunIdOrderByIdAsc(run.getId()).size();

        ClaimedRun claim = ops.claimNext("runner-2").orElseThrow();

        assertEquals(run.getId(), claim.runId());
        assertEquals(RunStatus.TESTING, claim.status());
        Run stored = fixtures.reload(run.getId());
        assertEquals(RunStatus.TESTING, stored.getStatus());
        assertEquals("runner-2", stored.getRunnerId());
        assertEquals(eventsBefore, eventRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    @Test
    void emptyQueueClaimsNothing() {
        assertTrue(ops.claimNext("runner-1").isEmpty());
    }

    @Test
    void neverClaimsRunsThatAreNotWaitingForAWorker() {
        // Owned by a runner already.
        fixtures.claimedAnalyzing(owner.id(), "runner-1");
        fixtures.claimedTesting(owner.id(), "runner-1");
        fixtures.claimedInvestigating(owner.id(), "runner-1");
        // Waiting for a human.
        ClaimedRun plan = fixtures.claimedAnalyzing(owner.id(), "runner-1");
        ops.advance(plan.runId(), "runner-1", RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK);
        ClaimedRun fix = fixtures.claimedInvestigating(owner.id(), "runner-1");
        ops.advance(fix.runId(), "runner-1", RunStatus.INVESTIGATING, RunStatus.AWAITING_FIX_OK);
        // Final.
        Run done = fixtures.queued(owner.id());
        machine.transition(done.getId(), RunStatus.CANCELLED, RunActor.USER, "x");
        Run failed = fixtures.queued(owner.id());
        machine.transition(failed.getId(), RunStatus.FAILED, RunActor.SYSTEM, "x");
        ClaimedRun finished = fixtures.claimedInvestigating(owner.id(), "runner-1");
        ops.advance(finished.runId(), "runner-1", RunStatus.INVESTIGATING, RunStatus.DONE);

        assertEquals(Optional.empty(), ops.claimNext("runner-2"));
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    /**
     * A holds the claim on the oldest Run (transaction open). B must not wait
     * for it and must not get it: SKIP LOCKED hands B the next Run at once.
     */
    @Test
    void aLockedRunIsSkippedNotWaitedFor() throws Exception {
        Run older = fixtures.queued(owner.id());
        Run newer = fixtures.queued(owner.id());

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<ClaimedRun> a = executor.submit(() -> tx.execute(status -> {
            ClaimedRun claim = ops.claimNext("runner-A").orElseThrow();
            aClaimed.countDown();
            await(releaseA);
            return claim;
        }));
        assertTrue(aClaimed.await(10, TimeUnit.SECONDS));

        Future<Optional<ClaimedRun>> b = executor.submit(() -> ops.claimNext("runner-B"));
        Optional<ClaimedRun> bClaim = b.get(2, TimeUnit.SECONDS); // must not block

        Future<Optional<ClaimedRun>> c = executor.submit(() -> ops.claimNext("runner-C"));
        Optional<ClaimedRun> cClaim = c.get(2, TimeUnit.SECONDS); // nothing left, no wait

        releaseA.countDown();
        assertEquals(older.getId(), a.get(10, TimeUnit.SECONDS).runId());
        assertEquals(newer.getId(), bClaim.orElseThrow().runId());
        assertTrue(cClaim.isEmpty());

        assertEquals("runner-A", fixtures.reload(older.getId()).getRunnerId());
        assertEquals("runner-B", fixtures.reload(newer.getId()).getRunnerId());
    }

    /** 30 Runs, 8 workers claiming until the queue is empty: each Run exactly once. */
    @Test
    void parallelWorkersClaimEveryRunExactlyOnce() throws Exception {
        int runs = 30;
        Set<UUID> created = new HashSet<>();
        for (int i = 0; i < runs; i++) {
            created.add(fixtures.queued(owner.id()).getId());
        }

        ConcurrentLinkedQueue<ClaimedRun> claims = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String runner = "runner-" + w;
            workers.add(executor.submit(() -> {
                await(start);
                Optional<ClaimedRun> claim;
                while ((claim = ops.claimNext(runner)).isPresent()) {
                    claims.add(claim.get());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> worker : workers) {
            worker.get(60, TimeUnit.SECONDS);
        }

        List<UUID> claimedIds = claims.stream().map(ClaimedRun::runId).toList();
        assertEquals(runs, claimedIds.size(), "a Run was claimed more than once or missed");
        assertEquals(created, new HashSet<>(claimedIds));

        for (ClaimedRun claim : claims) {
            Run stored = fixtures.reload(claim.runId());
            assertEquals(RunStatus.ANALYZING, stored.getStatus());
            assertEquals(claim.runnerId(), stored.getRunnerId());
            long claimEvents = eventRepository.findByRunIdOrderByIdAsc(claim.runId()).stream()
                    .filter(e -> e.getToStatus() == RunStatus.ANALYZING)
                    .count();
            assertEquals(1, claimEvents);
        }
    }

    @Test
    void claimedRunsHaveNoRunnerLeftOverAfterParking() {
        ClaimedRun claim = fixtures.claimedAnalyzing(owner.id(), "runner-1");
        ops.advance(claim.runId(), "runner-1", RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK);

        Run parked = fixtures.reload(claim.runId());
        assertNull(parked.getRunnerId());
        assertFalse(parked.isCancelRequested());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

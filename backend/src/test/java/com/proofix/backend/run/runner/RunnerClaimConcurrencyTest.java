package com.proofix.backend.run.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.proofix.backend.run.Run;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.run.RunnerClaim;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * Owner-scoped claim under concurrency (FOR UPDATE SKIP LOCKED): two runners
 * of the same owner never get the same Run, and never another owner's Run.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunnerClaimConcurrencyTest {

    @Autowired
    private RunWorkerOps ops;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunnerKeyService keyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    private RunnerFixtures fixtures;
    private ExecutorService executor;
    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine);
        executor = Executors.newFixedThreadPool(8);
        alice = fixtures.user();
        bob = fixtures.user();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        fixtures.deleteAll();
    }

    private static String runner(int n) {
        return UUID.randomUUID() + ":box-" + n;
    }

    @RepeatedTest(5)
    void twoRunnersRacingForOneRunOnlyOneGetsIt() throws Exception {
        Run run = fixtures.queued(alice);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<RunnerClaim>>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String runnerId = runner(i);
            Callable<Optional<RunnerClaim>> call = () -> {
                start.await();
                return ops.claimNextQueuedForOwner(alice.id(), runnerId);
            };
            futures.add(executor.submit(call));
        }
        start.countDown();

        int claimed = 0;
        for (Future<Optional<RunnerClaim>> future : futures) {
            Optional<RunnerClaim> claim = future.get(20, TimeUnit.SECONDS);
            if (claim.isPresent()) {
                claimed++;
                assertEquals(run.getId(), claim.get().runId());
            }
        }
        assertEquals(1, claimed);
        assertEquals("ANALYZING", fixtures.status(run.getId()));
    }

    @Test
    void manyRunnersClaimEveryRunExactlyOnceAndNeverAnotherOwners() throws Exception {
        Set<UUID> alices = new HashSet<>();
        Set<UUID> bobs = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            alices.add(fixtures.queued(alice).getId());
            bobs.add(fixtures.queued(bob).getId());
        }

        ConcurrentLinkedQueue<UUID> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String runnerId = runner(i);
            futures.add(executor.submit(() -> {
                start.await();
                Optional<RunnerClaim> claim;
                while ((claim = ops.claimNextQueuedForOwner(alice.id(), runnerId)).isPresent()) {
                    claimed.add(claim.get().runId());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        assertEquals(10, claimed.size(), "a Run was claimed twice or missed");
        assertEquals(alices, new HashSet<>(claimed));
        for (UUID id : bobs) {
            assertEquals("QUEUED", fixtures.status(id), "Bob's Run was touched");
        }
    }

    /** SKIP LOCKED: while one runner holds the oldest Run, the other gets the next at once. */
    @Test
    void aLockedRunIsSkippedNotWaitedFor() throws Exception {
        Run first = fixtures.queued(alice);
        Run second = fixtures.queued(alice);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Future<Optional<RunnerClaim>> a = executor.submit(() -> tx.execute(status -> {
            Optional<RunnerClaim> claim = ops.claimNextQueuedForOwner(alice.id(), runner(1));
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return claim;
        }));
        assertTrue(held.await(10, TimeUnit.SECONDS));

        Future<Optional<RunnerClaim>> b = executor.submit(
                () -> ops.claimNextQueuedForOwner(alice.id(), runner(2)));
        Optional<RunnerClaim> bClaim = b.get(3, TimeUnit.SECONDS);

        release.countDown();
        Optional<RunnerClaim> aClaim = a.get(10, TimeUnit.SECONDS);

        assertEquals(first.getId(), aClaim.orElseThrow().runId());
        assertEquals(second.getId(), bClaim.orElseThrow().runId());
    }

    @Test
    void blankRunnerIdIsRefused() {
        fixtures.queued(alice);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ops.claimNextQueuedForOwner(alice.id(), " "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ops.claimNextQueuedForOwner(null, "x:y"));
    }
}

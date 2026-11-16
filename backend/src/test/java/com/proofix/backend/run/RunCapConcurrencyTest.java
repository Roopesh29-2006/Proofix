package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.run.RunTestUsers.TestUser;
import com.proofix.backend.security.JwtService;
import com.proofix.backend.service.RepositoryAnalysisService;

/**
 * The per-user cap (default 3) must hold under concurrency: the check and
 * the insert happen under a lock on the user's row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunCapConcurrencyTest {

    private static final String REPO = "https://github.com/octo/demo";
    private static final RunBudgets BUDGETS = new RunBudgets(30, 50, 100);
    private static final int CAP = 3;

    @LocalServerPort
    private int port;

    @Autowired
    private RunLifecycle lifecycle;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private RepositoryAnalysisService repositoryAnalysisService;

    private RunTestUsers users;
    private ExecutorService executor;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() {
        users = new RunTestUsers(jdbc, jwtService);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        users.deleteAll();
    }

    /**
     * Deterministic proof of the lock: transaction A creates the 3rd Run and
     * stays open. B must block (not just lose a race) until A commits, then
     * see 3 Runs and fail with 409. Without the lock B would not block and
     * would insert a 4th Run.
     */
    @Test
    void secondCreateWaitsForTheFirstAndThenHitsTheCap() throws Exception {
        TestUser user = users.create();
        machine.create(user.id(), REPO, BUDGETS);
        machine.create(user.id(), REPO, BUDGETS);

        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<Run> a = executor.submit(() -> tx.execute(status -> {
            Run run = lifecycle.createWithinCap(user.id(), REPO, BUDGETS, CAP);
            aInserted.countDown();
            await(releaseA);
            return run;
        }));
        assertTrue(aInserted.await(10, TimeUnit.SECONDS), "A did not insert");

        Future<Run> b = executor.submit(
                () -> lifecycle.createWithinCap(user.id(), REPO, BUDGETS, CAP));

        assertThrows(TimeoutException.class, () -> b.get(750, TimeUnit.MILLISECONDS),
                "B was not blocked by A's lock");

        releaseA.countDown();
        a.get(10, TimeUnit.SECONDS);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> b.get(10, TimeUnit.SECONDS));
        assertInstanceOf(ConflictException.class, failure.getCause());
        assertEquals(3, users.runCount(user.id()));
    }

    /** Two parallel POSTs at the boundary (2 of 3 used): exactly one wins. */
    @RepeatedTest(5)
    void twoParallelPostsAtTheBoundaryCannotBothSucceed() throws Exception {
        TestUser user = users.create();
        machine.create(user.id(), REPO, BUDGETS);
        machine.create(user.id(), REPO, BUDGETS);

        List<Integer> statuses = parallelPosts(user, 2);

        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(3, users.runCount(user.id()));
    }

    /** A burst of 8 parallel POSTs from an empty account: exactly 3 succeed. */
    @Test
    void burstOfParallelPostsCreatesExactlyTheCap() throws Exception {
        TestUser user = users.create();

        List<Integer> statuses = parallelPosts(user, 8);

        assertEquals(CAP, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(8 - CAP, statuses.stream().filter(s -> s == 409).count(),
                statuses.toString());
        assertEquals(CAP, users.runCount(user.id()));
    }

    private List<Integer> parallelPosts(TestUser user, int count) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                await(start);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/runs"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + user.token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"repoUrl\": \"" + REPO + "\"}"))
                        .build();
                return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        return statuses;
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

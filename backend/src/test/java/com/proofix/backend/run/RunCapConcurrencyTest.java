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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

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
 * The per-user caps (3 QUEUED + active, 10 unfinished) must hold under
 * concurrency: counts and the insert or approval happen under a
 * FOR NO KEY UPDATE lock on the user's row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunCapConcurrencyTest {

    private static final String REPO = "https://github.com/octo/demo";
    private static final RunBudgets BUDGETS = new RunBudgets(30, 50, 100);
    private static final RunLimits LIMITS = new RunLimits(3, 10);

    @LocalServerPort
    private int port;

    @Autowired
    private RunLifecycle lifecycle;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunRepository runRepository;

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

    private Run awaitingPlan(UUID owner) {
        Run run = machine.create(owner, REPO, BUDGETS);
        machine.transition(run.getId(), RunStatus.ANALYZING, RunActor.SYSTEM, "fixture");
        return machine.transition(run.getId(), RunStatus.AWAITING_PLAN_OK, RunActor.SYSTEM,
                "fixture");
    }

    /**
     * Runs {@code first} in a transaction that stays open after it returns,
     * then starts {@code second}. Returns second's future while first still
     * holds its locks, plus a handle to commit first.
     */
    private <T> HeldTransaction<T> holdOpen(Function<Void, T> first) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Future<T> future = executor.submit(() -> tx.execute(status -> {
            T result = first.apply(null);
            done.countDown();
            await(release);
            return result;
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS), "first transaction did not get going");
        return new HeldTransaction<>(future, release);
    }

    private record HeldTransaction<T>(Future<T> future, CountDownLatch release) {
        T commit() throws Exception {
            release.countDown();
            return future.get(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // Create: active cap
    // ------------------------------------------------------------------

    /**
     * Deterministic proof of the lock: A creates the 3rd Run and stays open.
     * B must block (not just lose a race) until A commits, then fail with 409.
     */
    @Test
    void secondCreateWaitsForTheFirstAndThenHitsTheCap() throws Exception {
        TestUser user = users.create();
        machine.create(user.id(), REPO, BUDGETS);
        machine.create(user.id(), REPO, BUDGETS);

        HeldTransaction<Run> a = holdOpen(
                x -> lifecycle.createWithinLimits(user.id(), REPO, BUDGETS, LIMITS));
        Future<Run> b = executor.submit(
                () -> lifecycle.createWithinLimits(user.id(), REPO, BUDGETS, LIMITS));

        assertThrows(TimeoutException.class, () -> b.get(750, TimeUnit.MILLISECONDS),
                "B was not blocked by A's lock");

        a.commit();
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

        List<Integer> statuses = parallel(2, () -> post(user, "/api/runs", true));

        assertExactlyOne(201, statuses);
        assertEquals(3, users.runCount(user.id()));
    }

    /** A burst of 8 parallel POSTs from an empty account: exactly 3 succeed. */
    @Test
    void burstOfParallelPostsCreatesExactlyTheCap() throws Exception {
        TestUser user = users.create();

        List<Integer> statuses = parallel(8, () -> post(user, "/api/runs", true));

        assertEquals(3, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(3, users.runCount(user.id()));
    }

    // ------------------------------------------------------------------
    // Create: unfinished cap (QUEUED + active + AWAITING)
    // ------------------------------------------------------------------

    @RepeatedTest(3)
    void twoParallelPostsAtTheUnfinishedBoundaryCannotBothSucceed() throws Exception {
        TestUser user = users.create();
        for (int i = 0; i < 9; i++) {
            awaitingPlan(user.id());
        }

        List<Integer> statuses = parallel(2, () -> post(user, "/api/runs", true));

        assertExactlyOne(201, statuses);
        assertEquals(10, users.runCount(user.id()));
    }

    // ------------------------------------------------------------------
    // Approve: active cap re-checked under the same lock
    // ------------------------------------------------------------------

    @Test
    void secondApproveWaitsForTheFirstAndThenHitsTheCap() throws Exception {
        TestUser user = users.create();
        machine.create(user.id(), REPO, BUDGETS);
        machine.create(user.id(), REPO, BUDGETS);
        Run p1 = awaitingPlan(user.id());
        Run p2 = awaitingPlan(user.id());

        HeldTransaction<Run> a = holdOpen(x -> lifecycle.approve(user.id(), p1.getId(), LIMITS));
        Future<Run> b = executor.submit(() -> lifecycle.approve(user.id(), p2.getId(), LIMITS));

        assertThrows(TimeoutException.class, () -> b.get(750, TimeUnit.MILLISECONDS),
                "second approve was not blocked by the first");

        assertEquals(RunStatus.TESTING, a.commit().getStatus());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> b.get(10, TimeUnit.SECONDS));
        assertInstanceOf(ConflictException.class, failure.getCause());
        assertEquals(RunStatus.AWAITING_PLAN_OK,
                runRepository.findById(p2.getId()).orElseThrow().getStatus());
    }

    @RepeatedTest(5)
    void twoParallelApprovesAtTheBoundaryCannotBothSucceed() throws Exception {
        TestUser user = users.create();
        machine.create(user.id(), REPO, BUDGETS);
        machine.create(user.id(), REPO, BUDGETS);
        Run p1 = awaitingPlan(user.id());
        Run p2 = awaitingPlan(user.id());

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> a = executor.submit(() -> {
            await(start);
            return post(user, "/api/runs/" + p1.getId() + "/approve", false);
        });
        Future<Integer> b = executor.submit(() -> {
            await(start);
            return post(user, "/api/runs/" + p2.getId() + "/approve", false);
        });
        start.countDown();
        List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

        assertExactlyOne(200, statuses);
        long testing = runRepository.findAll().stream()
                .filter(r -> r.getOwnerUserId().equals(user.id()))
                .filter(r -> r.getStatus() == RunStatus.TESTING)
                .count();
        assertEquals(1, testing);
    }

    // ------------------------------------------------------------------
    // Lock strength: FOR NO KEY UPDATE does not block foreign-key inserts
    // ------------------------------------------------------------------

    @Test
    void capLockDoesNotBlockOtherRowsThatReferenceTheUser() throws Exception {
        TestUser user = users.create();

        HeldTransaction<Run> a = holdOpen(
                x -> lifecycle.createWithinLimits(user.id(), REPO, BUDGETS, LIMITS));

        // A projects row has a foreign key to users; its key-share lock must
        // not wait for the cap lock.
        Future<Integer> project = executor.submit(() -> jdbc.update(
                "INSERT INTO projects (user_id, project_name, project_status) "
                        + "VALUES (?, 'lock-probe', 'ACTIVE')", user.id()));
        assertEquals(1, project.get(2, TimeUnit.SECONDS), "project insert was blocked");

        a.commit();
        assertEquals(1, users.runCount(user.id()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int post(TestUser user, String path, boolean withBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + user.token());
        if (withBody) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"repoUrl\": \"" + REPO + "\"}"));
        } else {
            request.POST(HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private interface Call {
        int run() throws Exception;
    }

    private List<Integer> parallel(int count, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                await(start);
                return call.run();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        return statuses;
    }

    private static void assertExactlyOne(int success, List<Integer> statuses) {
        assertEquals(1, statuses.stream().filter(s -> s == success).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
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

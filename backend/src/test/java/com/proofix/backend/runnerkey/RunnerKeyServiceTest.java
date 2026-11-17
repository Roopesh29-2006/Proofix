package com.proofix.backend.runnerkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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

import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.run.MutableClock;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/** RunnerKeyService against proofix_test: authenticate, revoke, expiry, throttle, cap. */
@SpringBootTest
@ActiveProfiles("test")
class RunnerKeyServiceTest {

    @Autowired
    private RunnerKeyService service;

    @Autowired
    private RunnerKeyRepository repository;

    @Autowired
    private RunnerKeyProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    private RunnerKeyTestUsers users;
    private TestUser alice;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        users = new RunnerKeyTestUsers(jdbc, jwtService);
        alice = users.create();
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        users.deleteAll();
    }

    private OffsetDateTime lastUsedAt(UUID keyId) {
        return jdbc.queryForObject("SELECT last_used_at FROM runner_keys WHERE id = ?",
                OffsetDateTime.class, keyId);
    }

    // ------------------------------------------------------------------
    // authenticate
    // ------------------------------------------------------------------

    @Test
    void validKeyAuthenticatesAsItsOwner() {
        CreatedRunnerKey created = service.create(alice.id(), "laptop");

        Optional<RunnerKeyIdentity> identity = service.authenticate(created.plaintext());

        assertTrue(identity.isPresent());
        assertEquals(created.key().getId(), identity.get().keyId());
        assertEquals(alice.id(), identity.get().ownerUserId());
    }

    @Test
    void unknownAndMalformedKeysAreRejected() {
        service.create(alice.id(), "laptop");

        assertTrue(service.authenticate(RunnerKeyCodec.generate()).isEmpty());
        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("").isEmpty());
        assertTrue(service.authenticate("dbr_short").isEmpty());
        assertTrue(service.authenticate(alice.token()).isEmpty());
    }

    @Test
    void theHashIsNotAcceptedAsAKey() {
        CreatedRunnerKey created = service.create(alice.id(), "laptop");
        String hash = RunnerKeyCodec.hash(created.plaintext());

        assertTrue(service.authenticate(hash).isEmpty());
        assertTrue(service.authenticate("dbr_" + hash).isEmpty());
    }

    @Test
    void revokeBlocksTheVeryNextAuthentication() {
        CreatedRunnerKey created = service.create(alice.id(), "laptop");
        assertTrue(service.authenticate(created.plaintext()).isPresent());

        service.revoke(alice.id(), created.key().getId());

        assertTrue(service.authenticate(created.plaintext()).isEmpty());
    }

    @Test
    void expiredKeyIsRejected() {
        CreatedRunnerKey created = service.create(alice.id(), "laptop");
        jdbc.update("UPDATE runner_keys SET expires_at = now() - interval '1 second' WHERE id = ?",
                created.key().getId());

        assertTrue(service.authenticate(created.plaintext()).isEmpty());
    }

    @Test
    void keyWithAFutureExpiryStillWorks() {
        CreatedRunnerKey created = service.create(alice.id(), "laptop");
        jdbc.update("UPDATE runner_keys SET expires_at = now() + interval '1 hour' WHERE id = ?",
                created.key().getId());

        assertTrue(service.authenticate(created.plaintext()).isPresent());
    }

    @Test
    void keyOfADeletedUserIsGoneWithTheUser() {
        TestUser ghost = users.create();
        CreatedRunnerKey created = service.create(ghost.id(), "laptop");

        jdbc.update("DELETE FROM users WHERE user_id = ?", ghost.id());

        assertEquals(0, users.keyCount(ghost.id()));
        assertTrue(service.authenticate(created.plaintext()).isEmpty());
    }

    @Test
    void revokeOfAnotherUsersKeyIsNotFound() {
        TestUser bob = users.create();
        CreatedRunnerKey bobs = service.create(bob.id(), "bobs");

        assertThrows(ResourceNotFoundException.class,
                () -> service.revoke(alice.id(), bobs.key().getId()));
        assertTrue(service.authenticate(bobs.plaintext()).isPresent());
    }

    // ------------------------------------------------------------------
    // last_used_at: at most once a minute
    // ------------------------------------------------------------------

    @Test
    void lastUsedAtIsUpdatedAtMostOnceAMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2030-01-01T10:00:00Z"));
        RunnerKeyService clocked = new RunnerKeyService(repository, properties, clock);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CreatedRunnerKey created = tx.execute(s -> clocked.create(alice.id(), "laptop"));
        UUID id = created.key().getId();
        assertNull(lastUsedAt(id));

        tx.execute(s -> clocked.authenticate(created.plaintext()));
        OffsetDateTime first = lastUsedAt(id);
        assertEquals(Instant.parse("2030-01-01T10:00:00Z"), first.toInstant());

        clock.advance(Duration.ofSeconds(59));
        tx.execute(s -> clocked.authenticate(created.plaintext()));
        assertEquals(first.toInstant(), lastUsedAt(id).toInstant(), "updated within the minute");

        clock.advance(Duration.ofSeconds(1));
        tx.execute(s -> clocked.authenticate(created.plaintext()));
        assertEquals(Instant.parse("2030-01-01T10:01:00Z"), lastUsedAt(id).toInstant());
    }

    @Test
    void rejectedKeysDoNotTouchLastUsedAt() {
        CreatedRunnerKey created = service.create(alice.id(), "laptop");
        service.revoke(alice.id(), created.key().getId());

        service.authenticate(created.plaintext());

        assertNull(lastUsedAt(created.key().getId()));
    }

    // ------------------------------------------------------------------
    // Cap under concurrency
    // ------------------------------------------------------------------

    @RepeatedTest(3)
    void parallelCreatesNeverExceedTheCap() throws Exception {
        assertEquals(5, properties.maxActivePerUser());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreatedRunnerKey>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "k" + i;
            Callable<CreatedRunnerKey> call = () -> {
                start.await();
                return service.create(alice.id(), name);
            };
            futures.add(executor.submit(call));
        }
        start.countDown();

        int created = 0;
        int conflicts = 0;
        for (Future<CreatedRunnerKey> future : futures) {
            try {
                assertNotNull(future.get(20, TimeUnit.SECONDS));
                created++;
            } catch (ExecutionException e) {
                assertInstanceOf(ConflictException.class, e.getCause());
                conflicts++;
            }
        }
        assertEquals(5, created);
        assertEquals(3, conflicts);
        assertEquals(5, users.keyCount(alice.id()));
    }
}

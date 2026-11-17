package com.proofix.backend.run.runner;

import static com.proofix.backend.run.RunStatus.ANALYZING;
import static com.proofix.backend.run.RunStatus.AWAITING_PLAN_OK;
import static com.proofix.backend.run.RunStatus.CANCELLED;
import static com.proofix.backend.run.runner.RunnerFixtures.INSTANCE;
import static com.proofix.backend.run.runner.RunnerFixtures.REPO;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerId;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.proofix.backend.run.Run;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * POST /api/runner/claim: owner-scoped, QUEUED only. The instance comes from
 * X-Runner-Instance only; the body must be empty or {}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerClaimApiTest {

    private static final String CLAIM = "/api/runner/claim";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RunnerKeyService keyService;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunWorkerOps ops;

    private RunnerFixtures fixtures;
    private TestUser alice;
    private TestUser bob;
    private CreatedRunnerKey aliceKey;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        alice = fixtures.user();
        bob = fixtures.user();
        aliceKey = fixtures.key(alice);
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    private ResultActions claim(CreatedRunnerKey key) throws Exception {
        return mockMvc.perform(runnerPost(CLAIM, key, INSTANCE));
    }

    private ResultActions claim(CreatedRunnerKey key, String body) throws Exception {
        return mockMvc.perform(runnerPost(CLAIM, key, INSTANCE, body));
    }

    @Test
    void claimsTheOwnersOldestQueuedRunAndMovesItToAnalyzing() throws Exception {
        Run first = fixtures.queued(alice);
        Run second = fixtures.queued(alice);

        claim(aliceKey).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(first.getId().toString())))
                .andExpect(jsonPath("$.repoUrl", is(REPO)))
                .andExpect(jsonPath("$.status", is("ANALYZING")))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.runnerId").doesNotExist())
                .andExpect(content().string(not(containsString(alice.id().toString()))));

        assertEquals("ANALYZING", fixtures.status(first.getId()));
        assertEquals(runnerId(aliceKey, INSTANCE), fixtures.runnerIdOf(first.getId()));
        assertEquals(runnerId(aliceKey, INSTANCE), fixtures.lastRunnerIdOf(first.getId()));
        assertNotNull(jdbc.queryForObject("SELECT heartbeat_at FROM runs WHERE id = ?",
                OffsetDateTime.class, first.getId()));
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT from_status, to_status, actor FROM run_events WHERE run_id = ? "
                        + "ORDER BY id DESC LIMIT 1", first.getId());
        assertEquals("QUEUED", event.get("from_status"));
        assertEquals("ANALYZING", event.get("to_status"));
        assertEquals("WORKER", event.get("actor"));

        claim(aliceKey, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(second.getId().toString())));
        claim(aliceKey).andExpect(status().isNoContent());
    }

    @Test
    void nothingWaitingIs204() throws Exception {
        claim(aliceKey).andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    /** Bob's Run is older, but Alice's runner must never see it. */
    @Test
    void neverClaimsAnotherUsersRun() throws Exception {
        Run bobs = fixtures.queued(bob);

        claim(aliceKey).andExpect(status().isNoContent());

        Run alices = fixtures.queued(alice);
        claim(aliceKey).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(alices.getId().toString())));
        claim(aliceKey).andExpect(status().isNoContent());

        assertEquals("QUEUED", fixtures.status(bobs.getId()));
        assertNull(fixtures.runnerIdOf(bobs.getId()));

        // Bob's own runner gets it.
        claim(fixtures.key(bob)).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId", is(bobs.getId().toString())));
    }

    /** Build 2A: approved TESTING Runs are not claimed (no endpoint continues them yet). */
    @Test
    void doesNotClaimApprovedTestingRuns() throws Exception {
        Run testing = fixtures.approvedTesting(alice);

        claim(aliceKey).andExpect(status().isNoContent());

        assertEquals("TESTING", fixtures.status(testing.getId()));
        assertNull(fixtures.runnerIdOf(testing.getId()));
    }

    @Test
    void doesNotClaimRunsInOtherStates() throws Exception {
        fixtures.run(alice, List.of(ANALYZING));
        fixtures.run(alice, List.of(ANALYZING, AWAITING_PLAN_OK));
        fixtures.run(alice, List.of(CANCELLED));

        claim(aliceKey).andExpect(status().isNoContent());
    }

    @Test
    void runnerIdComesFromTheKeyAndTheInstanceHeader() throws Exception {
        CreatedRunnerKey second = fixtures.key(alice);
        Run run = fixtures.queued(alice);
        String instance = "A-z_0.9-" + "x".repeat(55);
        assertEquals(63, instance.length());

        mockMvc.perform(runnerPost(CLAIM, second, instance)).andExpect(status().isOk());

        assertEquals(runnerId(second, instance), fixtures.runnerIdOf(run.getId()));
    }

    @Test
    void anyBodyOtherThanEmptyOrAnEmptyObjectIs400AndClaimsNothing() throws Exception {
        Run run = fixtures.queued(alice);
        List<String> bodies = List.of(
                "[]",
                "null",
                "\"box-1\"",
                "{\"instanceId\": \"box-1\"}",
                "{\"ownerUserId\": \"" + bob.id() + "\"}",
                "{\"userId\": \"" + bob.id() + "\"}",
                "{\"runnerId\": \"other\"}",
                "{} {}",
                "{",
                "not json");

        for (String body : bodies) {
            claim(aliceKey, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }

        assertEquals("QUEUED", fixtures.status(run.getId()));
        assertNull(fixtures.runnerIdOf(run.getId()));
    }

    @Test
    void aUserIdInTheBodyCannotRedirectTheClaim() throws Exception {
        Run bobs = fixtures.queued(bob);

        claim(aliceKey, "{\"ownerUserId\": \"" + bob.id() + "\"}")
                .andExpect(status().isBadRequest());

        assertEquals("QUEUED", fixtures.status(bobs.getId()));
    }
}

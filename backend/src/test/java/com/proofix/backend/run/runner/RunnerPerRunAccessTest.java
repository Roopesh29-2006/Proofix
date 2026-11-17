package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.NOT_FOUND_BODY;
import static com.proofix.backend.run.runner.RunnerFixtures.VALID_FAIL;
import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerId;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * Per-Run runner calls (heartbeat, report-analysis, fail): only the runner
 * holding the Run (owner AND runner_id = keyId:instance) gets through.
 * Every other caller gets 404 with exactly the body of a missing Run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerPerRunAccessTest {

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
    private UUID aliceRun;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        alice = fixtures.user();
        bob = fixtures.user();
        aliceKey = fixtures.key(alice);
        aliceRun = fixtures.claimed(alice, aliceKey, "box-1");
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    private static MockHttpServletRequestBuilder call(String endpoint, UUID runId,
            CreatedRunnerKey key, String instance) {
        String path = "/api/runner/runs/" + runId + "/" + endpoint;
        return switch (endpoint) {
            case "heartbeat" -> runnerPost(path, key, instance);
            case "report-analysis" -> runnerPost(path, key, instance, VALID_REPORT);
            case "fail" -> runnerPost(path, key, instance, VALID_FAIL);
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    private ResultActions perform(String endpoint, UUID runId, CreatedRunnerKey key,
            String instance) throws Exception {
        return mockMvc.perform(call(endpoint, runId, key, instance));
    }

    private String notFound(String endpoint, UUID runId, CreatedRunnerKey key, String instance)
            throws Exception {
        return perform(endpoint, runId, key, instance)
                .andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND_BODY, true))
                .andReturn().getResponse().getContentAsString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void theHoldingRunnerIsAccepted(String endpoint) throws Exception {
        perform(endpoint, aliceRun, aliceKey, "box-1").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void anotherUsersKeyGets404AndChangesNothing(String endpoint) throws Exception {
        CreatedRunnerKey bobKey = fixtures.key(bob);

        notFound(endpoint, aliceRun, bobKey, "box-1");

        assertEquals("ANALYZING", fixtures.status(aliceRun));
        assertNull(fixtures.plan(aliceRun));
        assertNull(fixtures.failureCode(aliceRun));
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void sameKeyDifferentInstanceGets404(String endpoint) throws Exception {
        notFound(endpoint, aliceRun, aliceKey, "box-2");

        assertEquals("ANALYZING", fixtures.status(aliceRun));
        assertNull(fixtures.plan(aliceRun));
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void sameOwnerDifferentKeyGets404(String endpoint) throws Exception {
        notFound(endpoint, aliceRun, fixtures.key(alice), "box-1");

        assertEquals("ANALYZING", fixtures.status(aliceRun));
    }

    /**
     * Owner check, independent of the runner_id check: Bob's Run carries
     * Alice's exact runner id (corrupt data or a collision). Alice's runner
     * still must not reach it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void anotherOwnersRunIsNotFoundEvenWithAMatchingRunnerId(String endpoint) throws Exception {
        CreatedRunnerKey bobKey = fixtures.key(bob);
        UUID bobRun = fixtures.claimed(bob, bobKey, "box-1");
        String aliceRunner = runnerId(aliceKey, "box-1");
        jdbc.update("UPDATE runs SET runner_id = ?, last_runner_id = ? WHERE id = ?",
                aliceRunner, aliceRunner, bobRun);

        notFound(endpoint, bobRun, aliceKey, "box-1");

        assertEquals("ANALYZING", fixtures.status(bobRun));
        assertNull(fixtures.plan(bobRun));
        assertNull(fixtures.failureCode(bobRun));
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void everyRefusalLooksExactlyLikeAMissingRun(String endpoint) throws Exception {
        String missing = notFound(endpoint, UUID.randomUUID(), aliceKey, "box-1");
        String otherUser = notFound(endpoint, aliceRun, fixtures.key(bob), "box-1");
        String otherInstance = notFound(endpoint, aliceRun, aliceKey, "box-9");

        assertEquals(missing, otherUser);
        assertEquals(missing, otherInstance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "report-analysis", "fail"})
    void aQueuedRunNoRunnerHasClaimedIsNotFound(String endpoint) throws Exception {
        UUID queued = fixtures.queued(alice).getId();

        notFound(endpoint, queued, aliceKey, "box-1");

        assertEquals("QUEUED", fixtures.status(queued));
    }
}

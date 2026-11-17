package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.VALID_FAIL;
import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerId;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.proofix.backend.run.RunLifecycle;
import com.proofix.backend.run.RunLimits;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/runner/runs/{id}/report-analysis through the HTTP API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerReportAnalysisApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @Autowired
    private RunLifecycle lifecycle;

    private RunnerFixtures fixtures;
    private TestUser alice;
    private CreatedRunnerKey key;
    private UUID runId;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        alice = fixtures.user();
        key = fixtures.key(alice);
        runId = fixtures.claimed(alice, key, "box-1");
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    private ResultActions report(String body) throws Exception {
        return mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/report-analysis",
                key, "box-1", body));
    }

    private JsonNode storedPlan() {
        return JSON.readTree(fixtures.plan(runId));
    }

    @Test
    void validReportStoresTheCanonicalPlanAndAwaitsApproval() throws Exception {
        report(VALID_REPORT).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"AWAITING_PLAN_OK\"}", true));

        assertEquals("AWAITING_PLAN_OK", fixtures.status(runId));
        assertNull(fixtures.runnerIdOf(runId), "a waiting Run has no current runner");
        assertEquals(runnerId(key, "box-1"), fixtures.lastRunnerIdOf(runId));

        JsonNode plan = storedPlan();
        assertEquals(1, plan.get("schemaVersion").intValue());
        assertTrue(plan.get("journeys").isNull());
        assertEquals(0, plan.get("redactedFields").size());
        JsonNode recipe = plan.get("recipe");
        assertEquals("java-spring-boot", recipe.get("stack").stringValue());
        assertEquals("./mvnw -q package -DskipTests", recipe.get("buildCommand").stringValue());
        assertEquals("java -jar target/app.jar", recipe.get("startCommand").stringValue());
        assertEquals(8080, recipe.get("port").intValue());
        assertEquals(List.of("DB_URL", "DB_PASSWORD"),
                JSON.convertValue(recipe.get("envVarNames"), List.class));
        assertEquals("postgres", recipe.get("needsDatabase").stringValue());
        assertEquals(2, recipe.get("evidence").size());
        assertEquals(21, recipe.get("javaVersion").intValue());
        assertEquals("/actuator/health", recipe.get("healthCheckPath").stringValue());

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT from_status, to_status, actor FROM run_events WHERE run_id = ? "
                        + "ORDER BY id DESC LIMIT 1", runId);
        assertEquals("ANALYZING", event.get("from_status"));
        assertEquals("AWAITING_PLAN_OK", event.get("to_status"));
        assertEquals("WORKER", event.get("actor"));
    }

    @Test
    void optionalFieldsMayBeOmittedAndAreStoredAsNull() throws Exception {
        String minimal = "{\"schemaVersion\": 1, \"journeys\": null, \"recipe\": {"
                + "\"stack\": \"node\", \"buildCommand\": \"npm ci\", \"startCommand\": \"npm start\","
                + " \"port\": 3000, \"envVarNames\": [], \"evidence\": []}}";

        report(minimal).andExpect(status().isOk());

        JsonNode recipe = storedPlan().get("recipe");
        assertTrue(recipe.get("needsDatabase").isNull());
        assertTrue(recipe.get("javaVersion").isNull());
        assertTrue(recipe.get("healthCheckPath").isNull());
    }

    @Test
    void secretsInCommandsAndEvidenceAreRedactedBeforeStoringAndListed() throws Exception {
        // Built at runtime so no token-shaped literal sits in the source.
        String githubToken = "ghp_" + "A".repeat(36);
        String body = VALID_REPORT
                .replace("./mvnw -q package -DskipTests", "DB_PASSWORD=hunter2secret ./mvnw package")
                .replace("\"pom.xml\"", "\"pom.xml mentions " + githubToken + "\"");

        report(body).andExpect(status().isOk());

        String stored = fixtures.plan(runId);
        assertFalse(stored.contains("hunter2secret"), stored);
        assertFalse(stored.contains(githubToken), stored);
        JsonNode plan = storedPlan();
        assertTrue(plan.get("recipe").get("buildCommand").stringValue().contains("[REDACTED"));
        assertEquals(List.of("recipe.buildCommand", "recipe.evidence[0]"),
                JSON.convertValue(plan.get("redactedFields"), List.class));
    }

    @Test
    void aSecondReportIs409AndLeavesThePlanUnchanged() throws Exception {
        report(VALID_REPORT).andExpect(status().isOk());
        String first = fixtures.plan(runId);

        report(VALID_REPORT.replace("8080", "9090")).andExpect(status().isConflict());

        assertEquals(first, fixtures.plan(runId));
        assertEquals("AWAITING_PLAN_OK", fixtures.status(runId));
    }

    @Test
    void aPendingCancelWinsAndNothingIsStored() throws Exception {
        lifecycle.cancel(alice.id(), runId);

        report(VALID_REPORT).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"CANCELLED\"}", true));

        assertEquals("CANCELLED", fixtures.status(runId));
        assertNull(fixtures.plan(runId));
        Long awaitingEvents = jdbc.queryForObject("SELECT count(*) FROM run_events "
                + "WHERE run_id = ? AND to_status = 'AWAITING_PLAN_OK'", Long.class, runId);
        assertEquals(0L, awaitingEvents);
    }

    @Test
    void aReportOnAFinalRunIs409() throws Exception {
        assertTrue(ops.reapStaleHeartbeat(runId, OffsetDateTime.now().plusMinutes(1)));

        report(VALID_REPORT).andExpect(status().isConflict());

        assertEquals("FAILED", fixtures.status(runId));
        assertNull(fixtures.plan(runId));
    }

    @Test
    void reportAndFailOnAnApprovedTestingRunTheRunnerNoLongerHoldsAre409() throws Exception {
        report(VALID_REPORT).andExpect(status().isOk());
        lifecycle.approve(alice.id(), runId, new RunLimits(3, 10));
        assertEquals("TESTING", fixtures.status(runId));

        report(VALID_REPORT).andExpect(status().isConflict());
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/fail", key, "box-1", VALID_FAIL))
                .andExpect(status().isConflict());

        assertEquals("TESTING", fixtures.status(runId), "TESTING -> FAILED is legal, but not for "
                + "a runner that does not hold the Run");
    }

    @Test
    void invalidReportsAre400AndChangeNothing() throws Exception {
        for (String body : List.of(
                "",
                "{}",
                VALID_REPORT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                VALID_REPORT.replace("\"port\": 8080", "\"port\": \"8080\""),
                VALID_REPORT.replace("\"journeys\": null", "\"journeys\": []"),
                VALID_REPORT.replace("\"stack\"", "\"extra\": 1, \"stack\""))) {
            report(body).andExpect(status().isBadRequest());
        }

        assertEquals("ANALYZING", fixtures.status(runId));
        assertNull(fixtures.plan(runId));
    }

    @Test
    void anOversizedReportIs413() throws Exception {
        String big = VALID_REPORT.replace("\"pom.xml\"", "\"" + "x".repeat(70_000) + "\"");

        report(big).andExpect(status().isPayloadTooLarge());

        assertNull(fixtures.plan(runId));
    }
}

package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.proofix.backend.run.FailureCode;
import com.proofix.backend.run.RunLifecycle;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/** POST /api/runner/runs/{id}/fail. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerFailApiTest {

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

    private ResultActions fail(String body) throws Exception {
        return mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/fail", key, "box-1", body));
    }

    private static String failBody(String code, String reason) {
        return "{\"code\": " + code + ", \"reason\": " + reason + "}";
    }

    @ParameterizedTest
    @EnumSource(value = FailureCode.class, names = {"RUNNER_LOST", "BUDGET_EXCEEDED"},
            mode = EnumSource.Mode.EXCLUDE)
    void everyRunnerCodeFailsTheRun(FailureCode code) throws Exception {
        fail(failBody("\"" + code.name() + "\"", "\"it broke\"")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"FAILED\"}", true));

        assertEquals("FAILED", fixtures.status(runId));
        assertEquals(code.name(), fixtures.failureCode(runId));
        assertEquals("it broke", fixtures.failureReason(runId));
        assertNull(fixtures.runnerIdOf(runId));
    }

    @ParameterizedTest
    @EnumSource(value = FailureCode.class, names = {"RUNNER_LOST", "BUDGET_EXCEEDED"})
    void serverOnlyCodesAreRejected(FailureCode code) throws Exception {
        fail(failBody("\"" + code.name() + "\"", "\"x\"")).andExpect(status().isBadRequest());

        assertEquals("ANALYZING", fixtures.status(runId));
        assertNull(fixtures.failureCode(runId));
    }

    /** Second guard, below the controller: the worker step itself refuses server-only codes. */
    @ParameterizedTest
    @EnumSource(value = FailureCode.class, names = {"RUNNER_LOST", "BUDGET_EXCEEDED"})
    void theWorkerStepRefusesServerOnlyCodes(FailureCode code) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ops.failRunner(alice.id(), runId, RunnerFixtures.runnerId(key, "box-1"), code,
                        "x"));
        assertEquals("ANALYZING", fixtures.status(runId));
    }

    @Test
    void invalidBodiesAre400AndChangeNothing() throws Exception {
        for (String body : List.of(
                "",
                "{}",
                failBody("\"build_failed\"", "\"x\""),
                failBody("\"NOPE\"", "\"x\""),
                failBody("5", "\"x\""),
                failBody("null", "\"x\""),
                "{\"reason\": \"x\"}",
                "{\"code\": \"BUILD_FAILED\"}",
                failBody("\"BUILD_FAILED\"", "null"),
                failBody("\"BUILD_FAILED\"", "5"),
                failBody("\"BUILD_FAILED\"", "\"\""),
                failBody("\"BUILD_FAILED\"", "\"   \""),
                failBody("\"BUILD_FAILED\"", "\"\\n\\t\\u0000\""),
                failBody("\"BUILD_FAILED\"", "\"" + "r".repeat(2001) + "\""),
                "{\"code\": \"BUILD_FAILED\", \"reason\": \"x\", \"fixHint\": \"run rm -rf /\"}",
                "{\"code\": \"BUILD_FAILED\", \"reason\": \"x\", \"status\": \"CANCELLED\"}",
                "{\"code\": \"BUILD_FAILED\", \"code\": \"UNKNOWN\", \"reason\": \"x\"}")) {
            fail(body).andExpect(status().isBadRequest());
        }

        assertEquals("ANALYZING", fixtures.status(runId));
        assertNull(fixtures.failureCode(runId));
    }

    @Test
    void theReasonIsSanitizedRedactedAndCapped() throws Exception {
        String token = "ghp_" + "C".repeat(36);
        String raw = "line one\\nline\\ttwo\\u0007 token " + token + " " + "z".repeat(1500);

        fail(failBody("\"BUILD_FAILED\"", "\"" + raw + "\"")).andExpect(status().isOk());

        String stored = fixtures.failureReason(runId);
        assertTrue(stored.startsWith("line one line two token [REDACTED_GITHUB_TOKEN] zzz"), stored);
        assertFalse(stored.contains(token));
        assertEquals(500, stored.length());
        String note = jdbc.queryForObject("SELECT note FROM run_events WHERE run_id = ? "
                + "AND to_status = 'FAILED'", String.class, runId);
        assertEquals(stored, note);
    }

    @Test
    void aReasonOfExactly2000CharactersIsAccepted() throws Exception {
        fail(failBody("\"UNKNOWN\"", "\"" + "r".repeat(2000) + "\"")).andExpect(status().isOk());
        assertEquals(500, fixtures.failureReason(runId).length());
    }

    /** A runner-reported failure beats a pending cancel: the Run stays FAILED. */
    @Test
    void aPendingCancelDoesNotTurnAFailureIntoACancel() throws Exception {
        lifecycle.cancel(alice.id(), runId);

        fail(failBody("\"APP_DID_NOT_START\"", "\"crashed\"")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"FAILED\"}", true));

        assertEquals("FAILED", fixtures.status(runId));
        assertEquals("APP_DID_NOT_START", fixtures.failureCode(runId));
    }

    @Test
    void failOnAnAwaitingRunIs409() throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/report-analysis", key, "box-1",
                VALID_REPORT)).andExpect(status().isOk());

        fail(failBody("\"UNKNOWN\"", "\"x\"")).andExpect(status().isConflict());

        assertEquals("AWAITING_PLAN_OK", fixtures.status(runId));
        assertNull(fixtures.failureCode(runId));
    }

    @Test
    void failOnAFinalRunIs409AndKeepsTheFirstFailure() throws Exception {
        fail(failBody("\"BUILD_FAILED\"", "\"first\"")).andExpect(status().isOk());

        fail(failBody("\"UNKNOWN\"", "\"second\"")).andExpect(status().isConflict());

        assertEquals("BUILD_FAILED", fixtures.failureCode(runId));
        assertEquals("first", fixtures.failureReason(runId));
    }
}

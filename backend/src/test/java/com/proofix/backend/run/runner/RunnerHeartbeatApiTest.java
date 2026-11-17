package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.VALID_FAIL;
import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerId;
import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/** POST /api/runner/runs/{id}/heartbeat. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerHeartbeatApiTest {

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

    private ResultActions heartbeat() throws Exception {
        return mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/heartbeat", key, "box-1"));
    }

    private ResultActions expectHeartbeat(boolean cancelRequested, String status) throws Exception {
        return heartbeat().andExpect(status().isOk()).andExpect(content().json(
                "{\"cancelRequested\":" + cancelRequested + ",\"status\":\"" + status + "\"}", true));
    }

    private Map<String, Object> liveness() {
        return jdbc.queryForMap(
                "SELECT heartbeat_at, updated_at, version FROM runs WHERE id = ?", runId);
    }

    @Test
    void heldRunMovesTheHeartbeatOnly() throws Exception {
        jdbc.update("UPDATE runs SET heartbeat_at = now() - interval '20 seconds' WHERE id = ?",
                runId);
        Map<String, Object> before = liveness();

        expectHeartbeat(false, "ANALYZING");

        Map<String, Object> after = liveness();
        assertTrue(((java.sql.Timestamp) after.get("heartbeat_at"))
                .after((java.sql.Timestamp) before.get("heartbeat_at")));
        assertEquals(before.get("updated_at"), after.get("updated_at"));
        assertEquals(before.get("version"), after.get("version"));
        assertEquals("ANALYZING", fixtures.status(runId));
    }

    @Test
    void aPendingCancelIsReported() throws Exception {
        lifecycle.cancel(alice.id(), runId);
        assertEquals("ANALYZING", fixtures.status(runId), "cancel of a held Run only sets the flag");

        expectHeartbeat(true, "ANALYZING");
    }

    @Test
    void afterTheReportTheRunnerIsToldTheRunIsAwaitingApproval() throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/report-analysis", key, "box-1",
                VALID_REPORT)).andExpect(status().isOk());
        Map<String, Object> before = liveness();

        expectHeartbeat(false, "AWAITING_PLAN_OK");

        assertEquals(before, liveness(), "a heartbeat on a Run not held must write nothing");
    }

    @Test
    void afterTheReaperFailedTheRunTheRunnerIsToldToStop() throws Exception {
        assertTrue(ops.reapStaleHeartbeat(runId, OffsetDateTime.now().plusMinutes(1)));

        expectHeartbeat(true, "FAILED");
    }

    @Test
    void afterTheRunnerFailedItTheRunnerIsToldToStop() throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/fail", key, "box-1", VALID_FAIL))
                .andExpect(status().isOk());

        expectHeartbeat(true, "FAILED");
    }

    @Test
    void afterTheUserCancelledTheRunnerIsToldToStop() throws Exception {
        lifecycle.cancel(alice.id(), runId);
        assertEquals("CANCELLED", ops.checkpoint(runId, runnerId(key, "box-1")).name());

        expectHeartbeat(true, "CANCELLED");
    }

    @Test
    void afterApprovalAnUnclaimedTestingRunIsReportedAsTesting() throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/report-analysis", key, "box-1",
                VALID_REPORT)).andExpect(status().isOk());
        lifecycle.approve(alice.id(), runId, new com.proofix.backend.run.RunLimits(3, 10));

        expectHeartbeat(false, "TESTING");
    }

    @Test
    void aBodyIsRejected() throws Exception {
        for (String body : new String[] {"{}", "x", "{\"cancelRequested\": false}"}) {
            mockMvc.perform(runnerPost("/api/runner/runs/" + runId + "/heartbeat", key, "box-1",
                    body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void aMalformedRunIdIs400() throws Exception {
        mockMvc.perform(runnerPost("/api/runner/runs/not-a-uuid/heartbeat", key, "box-1"))
                .andExpect(status().isBadRequest());
    }
}

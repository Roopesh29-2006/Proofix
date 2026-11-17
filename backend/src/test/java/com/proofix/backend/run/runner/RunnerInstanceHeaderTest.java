package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.INSTANCE_HEADER;
import static com.proofix.backend.run.runner.RunnerFixtures.VALID_FAIL;
import static com.proofix.backend.run.runner.RunnerFixtures.VALID_REPORT;
import static com.proofix.backend.run.runner.RunnerFixtures.VERSION;
import static com.proofix.backend.run.runner.RunnerFixtures.VERSION_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.proofix.backend.run.RunRepository;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * X-Runner-Instance is required on every runner endpoint. Missing or
 * malformed -> 400 before any Run is looked up (after the key and version
 * checks, which keep their 401 / 426).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerInstanceHeaderTest {

    private static final String BAD_INSTANCE_BODY =
            "{\"error\":\"X-Runner-Instance header must be 1-63 characters of A-Z a-z 0-9 . _ -\"}";

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

    @MockitoSpyBean
    private RunRepository runRepository;

    private RunnerFixtures fixtures;
    private CreatedRunnerKey key;
    private UUID runId;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        TestUser alice = fixtures.user();
        key = fixtures.key(alice);
        runId = fixtures.claimed(alice, key, "box-1");
        clearInvocations(runRepository);
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    /** Every runner endpoint, with a valid body, minus the instance header. */
    private List<MockHttpServletRequestBuilder> endpoints() {
        return List.of(
                post("/api/runner/claim"),
                post("/api/runner/runs/" + runId + "/heartbeat"),
                post("/api/runner/runs/" + runId + "/report-analysis")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_REPORT),
                post("/api/runner/runs/" + runId + "/fail")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_FAIL));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + key.plaintext())
                .header(VERSION_HEADER, VERSION);
    }

    @Test
    void missingOrMalformedInstanceIs400BeforeAnyRunLookup() throws Exception {
        List<String> bad = Arrays.asList(null, "", " ", "box 1", "box:1", "box/1", "böx",
                "x".repeat(64), "box-1 ", "box\t1");
        int endpointCount = endpoints().size();
        for (int i = 0; i < endpointCount; i++) {
            for (String value : bad) {
                // A fresh builder per request: builders are mutable.
                MockHttpServletRequestBuilder request = authenticated(endpoints().get(i));
                if (value != null) {
                    request.header(INSTANCE_HEADER, value);
                }
                mockMvc.perform(request)
                        .andExpect(status().isBadRequest())
                        .andExpect(content().json(BAD_INSTANCE_BODY, true));
            }
        }

        verify(runRepository, never()).findByIdAndOwnerForUpdate(any(), any());
        verify(runRepository, never()).findByIdForUpdate(any());
        verify(runRepository, never()).lockNextQueuedIdForOwner(any());
        assertEquals("ANALYZING", fixtures.status(runId));
    }

    @Test
    void keyAndVersionAreCheckedBeforeTheInstance() throws Exception {
        for (MockHttpServletRequestBuilder endpoint : endpoints()) {
            mockMvc.perform(endpoint.header(VERSION_HEADER, VERSION))
                    .andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder endpoint : endpoints()) {
            mockMvc.perform(endpoint.header(HttpHeaders.AUTHORIZATION, "Bearer " + key.plaintext()))
                    .andExpect(status().isUpgradeRequired());
        }
    }

    @Test
    void aValidInstanceIsAccepted() throws Exception {
        mockMvc.perform(authenticated(post("/api/runner/runs/" + runId + "/heartbeat"))
                        .header(INSTANCE_HEADER, "box-1"))
                .andExpect(status().isOk());
    }
}

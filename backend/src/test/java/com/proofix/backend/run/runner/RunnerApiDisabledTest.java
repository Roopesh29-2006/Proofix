package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.runnerPost;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/** devbuddy.runner.api.enabled=false: every runner call is refused, user API unaffected. */
@SpringBootTest(properties = "devbuddy.runner.api.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerApiDisabledTest {

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
    private CreatedRunnerKey key;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        alice = fixtures.user();
        key = fixtures.key(alice);
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    @Test
    void runnerCallsAreRefusedEvenWithAValidKey() throws Exception {
        fixtures.queued(alice);
        mockMvc.perform(runnerPost("/api/runner/claim", key, "box-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userEndpointsStillWork() throws Exception {
        mockMvc.perform(get("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());
    }
}

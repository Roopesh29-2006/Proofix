package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.INSTANCE;
import static com.proofix.backend.run.runner.RunnerFixtures.INSTANCE_HEADER;
import static com.proofix.backend.run.runner.RunnerFixtures.VERSION;
import static com.proofix.backend.run.runner.RunnerFixtures.VERSION_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunWorkerOps;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyRepository;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;
import com.proofix.backend.security.RequestBodyLimitFilter;
import com.proofix.backend.security.RunnerKeyAuthenticationFilter;
import com.proofix.backend.security.RunnerVersionFilter;

/**
 * The two security chains: /api/runner/** accepts runner keys only (JWT
 * filter never runs there), every other endpoint accepts JWTs only (runner
 * filter never runs there). Plus version header (426) and body cap (413).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerAuthChainTest {

    private static final String UNAUTHENTICATED = "{\"error\":\"Authentication required\"}";
    private static final String CLAIM = "/api/runner/claim";
    private static final String CLAIM_BODY = "{}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private JwtService jwtService;

    @MockitoSpyBean
    private RunnerKeyService keyService;

    @MockitoSpyBean
    private RunnerKeyRepository keyRepository;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunWorkerOps ops;

    @Autowired
    private ApplicationContext context;

    private RunnerFixtures fixtures;
    private TestUser alice;
    private CreatedRunnerKey aliceKey;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine, ops);
        alice = fixtures.user();
        aliceKey = fixtures.key(alice);
        clearInvocations(jwtService, keyService, keyRepository);
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    private static MockHttpServletRequestBuilder claim() {
        return post(CLAIM).header(INSTANCE_HEADER, INSTANCE)
                .contentType(MediaType.APPLICATION_JSON).content(CLAIM_BODY);
    }

    private ResultActions claimWith(String authorization) throws Exception {
        return mockMvc.perform(claim()
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(VERSION_HEADER, VERSION));
    }

    private ResultActions claimWithKey(CreatedRunnerKey key) throws Exception {
        return claimWith("Bearer " + key.plaintext());
    }

    // ------------------------------------------------------------------
    // Runner chain: authentication
    // ------------------------------------------------------------------

    @Test
    void validKeyIsAccepted() throws Exception {
        claimWithKey(aliceKey).andExpect(status().isNoContent());
        verify(keyService, times(1)).authenticate(aliceKey.plaintext());
    }

    @Test
    void missingAuthorizationIs401() throws Exception {
        mockMvc.perform(claim().header(VERSION_HEADER, VERSION))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED, true));
    }

    @Test
    void aJwtIsRejectedOnRunnerEndpointsAndTheJwtFilterDoesNotRun() throws Exception {
        claimWith("Bearer " + alice.token())
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED, true));
        mockMvc.perform(post("/api/runner/runs/" + java.util.UUID.randomUUID() + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .header(VERSION_HEADER, VERSION))
                .andExpect(status().isUnauthorized());

        verify(jwtService, never()).isTokenValid(anyString());
        verify(jwtService, never()).extractUserId(anyString());
        verify(keyService, never()).authenticate(any());
    }

    @Test
    void aRunnerKeyIsRejectedOnUserEndpointsAndTheRunnerFilterDoesNotRun() throws Exception {
        String bearer = "Bearer " + aliceKey.plaintext();

        mockMvc.perform(get("/api/runs").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED, true));
        mockMvc.perform(get("/api/runner-keys").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runner-keys").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runs").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(VERSION_HEADER, VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/octo/demo\"}"))
                .andExpect(status().isUnauthorized());

        verify(keyService, never()).authenticate(any());
        verify(keyRepository, never()).findByKeyHash(any());
    }

    /** Positive control for the test above: the JWT filter does run on user endpoints. */
    @Test
    void theJwtFilterRunsOnUserEndpoints() throws Exception {
        mockMvc.perform(get("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());
        verify(jwtService, atLeastOnce()).isTokenValid(alice.token());
        verify(keyService, never()).authenticate(any());
    }

    @Test
    void malformedKeysAre401WithoutAnyDatabaseLookup() throws Exception {
        String body = aliceKey.plaintext().substring(4);
        List<String> headers = List.of(
                "Bearer " + aliceKey.plaintext().substring(0, 46),        // one short
                "Bearer " + aliceKey.plaintext() + "A",                   // one long
                "Bearer dbx_" + body,                                     // wrong prefix
                "Bearer DBR_" + body,
                "Bearer " + body,                                         // no prefix
                "Bearer dbr_" + body.substring(0, 42) + "+",              // not URL-safe
                "Bearer dbr_" + body.substring(0, 42) + "=",
                "Bearer " + aliceKey.plaintext() + " ",
                "Bearer  " + aliceKey.plaintext(),                        // double space
                "bearer " + aliceKey.plaintext(),                         // scheme case
                "Basic " + aliceKey.plaintext(),
                aliceKey.plaintext(),                                     // no scheme
                "Bearer ",
                "Bearer dbr_",
                "Bearer " + "dbr_" + "x".repeat(10_000));

        for (String header : headers) {
            claimWith(header)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json(UNAUTHENTICATED, true));
        }

        verify(keyService, never()).authenticate(any());
        verify(keyRepository, never()).findByKeyHash(any());
        assertNull(lastUsedAt(aliceKey), "a malformed key touched the database row");
    }

    /** Positive control: a well-formed but unknown key IS looked up (once), then 401. */
    @Test
    void wellFormedUnknownKeyIsLookedUpAndRejected() throws Exception {
        claimWith("Bearer " + com.proofix.backend.runnerkey.RunnerKeyCodec.generate())
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED, true));
        verify(keyRepository, times(1)).findByKeyHash(anyString());
    }

    @Test
    void revokedKeyIsRejectedOnTheVeryNextRequest() throws Exception {
        claimWithKey(aliceKey).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/runner-keys/" + aliceKey.key().getId() + "/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());

        claimWithKey(aliceKey)
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED, true));
    }

    @Test
    void expiredKeyIsRejected() throws Exception {
        jdbc.update("UPDATE runner_keys SET expires_at = now() - interval '1 second' WHERE id = ?",
                aliceKey.key().getId());

        claimWithKey(aliceKey).andExpect(status().isUnauthorized());
    }

    @Test
    void keyOfADeletedUserIsRejected() throws Exception {
        TestUser ghost = fixtures.user();
        CreatedRunnerKey ghostKey = fixtures.key(ghost);
        jdbc.update("DELETE FROM users WHERE user_id = ?", ghost.id());

        claimWithKey(ghostKey).andExpect(status().isUnauthorized());
    }

    @Test
    void successfulCallsRecordLastUsedAt() throws Exception {
        assertNull(lastUsedAt(aliceKey));
        claimWithKey(aliceKey).andExpect(status().isNoContent());
        assertNotNull(lastUsedAt(aliceKey));
    }

    // ------------------------------------------------------------------
    // Runner version header
    // ------------------------------------------------------------------

    @Test
    void missingMalformedOrOldVersionsGet426() throws Exception {
        String bearer = "Bearer " + aliceKey.plaintext();
        mockMvc.perform(claim().header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUpgradeRequired())
                .andExpect(header().string("X-Runner-Min-Version", "1.2.0"))
                .andExpect(content().json(
                        "{\"error\":\"Runner version 1.2.0 or newer is required\"}", true));

        for (String version : List.of("1.1.9", "0.9.0", "1.2", "1", "1.2.0.1", "v1.2.0",
                "1.2.0-beta", "abc", "", " 1.2.0", "1.2.x", "99999.0.0")) {
            mockMvc.perform(claim().header(HttpHeaders.AUTHORIZATION, bearer)
                            .header(VERSION_HEADER, version))
                    .andExpect(status().isUpgradeRequired());
        }
    }

    @Test
    void currentAndNewerVersionsAreAccepted() throws Exception {
        String bearer = "Bearer " + aliceKey.plaintext();
        for (String version : List.of("1.2.0", "1.2.1", "1.10.0", "2.0.0", "10.0.0")) {
            mockMvc.perform(claim().header(HttpHeaders.AUTHORIZATION, bearer)
                            .header(VERSION_HEADER, version))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void authenticationIsCheckedBeforeTheVersion() throws Exception {
        mockMvc.perform(claim().header(VERSION_HEADER, "0.0.1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(claim().header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userEndpointsDoNotNeedARunnerVersion() throws Exception {
        mockMvc.perform(get("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Body cap (64 KB) - chunked bodies are covered in RunnerBodyLimitHttpTest
    // ------------------------------------------------------------------

    @Test
    void bodiesOver64KbAreRejectedWith413() throws Exception {
        String big = "{\"pad\": \"" + "x".repeat(65_536) + "\"}";
        mockMvc.perform(post(CLAIM)
                        .header(INSTANCE_HEADER, INSTANCE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceKey.plaintext())
                        .header(VERSION_HEADER, VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().json("{\"error\":\"Request body too large\"}", true));
    }

    @Test
    void aBodyOfExactly64KbPassesTheCap() throws Exception {
        String prefix = "{}";
        String exact = prefix + " ".repeat(65_536 - prefix.length());
        assertEquals(65_536, exact.length());

        mockMvc.perform(post(CLAIM)
                        .header(INSTANCE_HEADER, INSTANCE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceKey.plaintext())
                        .header(VERSION_HEADER, VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exact))
                .andExpect(status().isNoContent());
    }

    @Test
    void userEndpointsAreNotSubjectToTheRunnerBodyCap() throws Exception {
        String big = "{\"name\": \"x\", \"pad\": \"" + "x".repeat(70_000) + "\"}";
        mockMvc.perform(post("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Filters are not global servlet filters
    // ------------------------------------------------------------------

    @Test
    void runnerFiltersAreNotRegisteredAsGlobalServletFilters() {
        assertTrue(context.getBeansOfType(RunnerKeyAuthenticationFilter.class).isEmpty());
        assertTrue(context.getBeansOfType(RunnerVersionFilter.class).isEmpty());
        assertTrue(context.getBeansOfType(RequestBodyLimitFilter.class).isEmpty());
        context.getBeansOfType(FilterRegistrationBean.class).values().forEach(r -> assertTrue(
                !(r.getFilter() instanceof RunnerKeyAuthenticationFilter
                        || r.getFilter() instanceof RunnerVersionFilter
                        || r.getFilter() instanceof RequestBodyLimitFilter)
                        || !r.isEnabled()));
    }

    private OffsetDateTime lastUsedAt(CreatedRunnerKey key) {
        return jdbc.queryForObject("SELECT last_used_at FROM runner_keys WHERE id = ?",
                OffsetDateTime.class, key.key().getId());
    }
}

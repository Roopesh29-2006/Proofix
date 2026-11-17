package com.proofix.backend.runnerkey;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtAuthenticationFilter;
import com.proofix.backend.security.JwtService;

/** /api/runner-keys through the real security chain, against proofix_test. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerKeyApiIntegrationTest {

    private static final String NOT_FOUND_BODY = "{\"error\":\"Runner key not found\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ApplicationContext context;

    private RunnerKeyTestUsers users;
    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void setUp() {
        users = new RunnerKeyTestUsers(jdbc, jwtService);
        alice = users.create();
        bob = users.create();
    }

    @AfterEach
    void tearDown() {
        users.deleteAll();
    }

    private ResultActions create(TestUser user, String body) throws Exception {
        return mockMvc.perform(post("/api/runner-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions createNamed(TestUser user, String name) throws Exception {
        return create(user, "{\"name\": \"" + name + "\"}");
    }

    private Map<String, Object> createdKey(TestUser user, String name) throws Exception {
        MvcResult result = createNamed(user, name).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$");
    }

    private ResultActions list(TestUser user) throws Exception {
        return mockMvc.perform(get("/api/runner-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()));
    }

    private ResultActions revoke(TestUser user, Object id) throws Exception {
        return mockMvc.perform(post("/api/runner-keys/" + id + "/revoke")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()));
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void createReturnsThePlaintextKeyOnceAndStoresOnlyItsHash() throws Exception {
        MvcResult result = createNamed(alice, "laptop")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/runner-keys/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name", is("laptop")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.keyHash").doesNotExist())
                .andReturn();

        String key = JsonPath.read(result.getResponse().getContentAsString(), "$.key");
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertTrue(RunnerKeyCodec.looksLikeKey(key));

        String storedHash = jdbc.queryForObject(
                "SELECT key_hash FROM runner_keys WHERE id = ?::uuid", String.class, id);
        assertEquals(RunnerKeyCodec.hash(key), storedHash);
        assertFalse(storedHash.contains(key.substring(4)));

        // Never returned again.
        String listed = list(alice).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(listed.contains(key), "list returned the plaintext key");
        assertFalse(listed.contains(storedHash), "list returned the hash");
        String revoked = revoke(alice, id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(revoked.contains(key));
        assertFalse(revoked.contains(storedHash));
    }

    @Test
    void namesAreTrimmedAndLimitedTo60Characters() throws Exception {
        createNamed(alice, "  ci box  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("ci box")));
        createNamed(alice, "x".repeat(60)).andExpect(status().isCreated());

        createNamed(alice, "x".repeat(61)).andExpect(status().isBadRequest());
        createNamed(alice, "   ").andExpect(status().isBadRequest());
        createNamed(alice, "").andExpect(status().isBadRequest());
        createNamed(alice, "bad\\u0000name").andExpect(status().isBadRequest());
        createNamed(alice, "bad\\nname").andExpect(status().isBadRequest());
        create(alice, "{}").andExpect(status().isBadRequest());
        create(alice, "{\"name\": null}").andExpect(status().isBadRequest());
        create(alice, "not json").andExpect(status().isBadRequest());

        assertEquals(2, users.keyCount(alice.id()));
    }

    @Test
    void createDoesNotAcceptAnOwnerFromTheBody() throws Exception {
        create(alice, "{\"name\": \"x\", \"ownerUserId\": \"" + bob.id() + "\"}")
                .andExpect(status().isCreated());

        assertEquals(1, users.keyCount(alice.id()));
        assertEquals(0, users.keyCount(bob.id()));
    }

    @Test
    void capOfFiveActiveKeysPerUser() throws Exception {
        for (int i = 0; i < 5; i++) {
            createNamed(alice, "k" + i).andExpect(status().isCreated());
        }
        createNamed(alice, "k5").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("5")));
        assertEquals(5, users.keyCount(alice.id()));

        // Another user's cap is separate.
        createNamed(bob, "b0").andExpect(status().isCreated());
    }

    @Test
    void revokedAndExpiredKeysDoNotCountTowardTheCap() throws Exception {
        Object first = null;
        for (int i = 0; i < 5; i++) {
            Map<String, Object> key = createdKey(alice, "k" + i);
            if (i == 0) {
                first = key.get("id");
            }
        }
        createNamed(alice, "full").andExpect(status().isConflict());

        revoke(alice, first).andExpect(status().isOk());
        Object sixth = createdKey(alice, "after-revoke").get("id");
        createNamed(alice, "full").andExpect(status().isConflict());

        jdbc.update("UPDATE runner_keys SET expires_at = now() - interval '1 minute' "
                + "WHERE id = ?::uuid", sixth);
        createNamed(alice, "after-expiry").andExpect(status().isCreated());
    }

    @Test
    void createRequiresAJwt() throws Exception {
        mockMvc.perform(post("/api/runner-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/runner-keys")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runner-keys/" + UUID.randomUUID() + "/revoke"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRunnerKeyIsNotAcceptedOnUserEndpoints() throws Exception {
        String key = (String) createdKey(alice, "laptop").get("key");

        mockMvc.perform(get("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isUnauthorized());
        assertEquals(1, users.keyCount(alice.id()));
    }

    @Test
    void createForADeletedUserIs401() throws Exception {
        TestUser ghost = users.create();
        jdbc.update("DELETE FROM users WHERE user_id = ?", ghost.id());

        createNamed(ghost, "x").andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    @Test
    void listShowsOnlyOwnKeysNewestFirstWithoutSecrets() throws Exception {
        createdKey(alice, "first");
        createdKey(alice, "second");
        createdKey(bob, "bobs");

        list(alice).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name", is("second")))
                .andExpect(jsonPath("$[1].name", is("first")))
                .andExpect(jsonPath("$[0].active", is(true)))
                .andExpect(jsonPath("$[0].lastUsedAt", nullValue()))
                .andExpect(jsonPath("$[0].revokedAt", nullValue()))
                .andExpect(jsonPath("$[0].expiresAt", nullValue()))
                .andExpect(jsonPath("$[0].key").doesNotExist())
                .andExpect(jsonPath("$[0].keyHash").doesNotExist())
                .andExpect(jsonPath("$[0].ownerUserId").doesNotExist())
                .andExpect(content().string(not(containsString("dbr_"))));
    }

    @Test
    void expiredKeysAreListedAsInactive() throws Exception {
        Object id = createdKey(alice, "old").get("id");
        jdbc.update("UPDATE runner_keys SET expires_at = now() - interval '1 second' "
                + "WHERE id = ?::uuid", id);

        list(alice).andExpect(jsonPath("$[0].active", is(false)))
                .andExpect(jsonPath("$[0].expiresAt").isNotEmpty());
    }

    // ------------------------------------------------------------------
    // Revoke
    // ------------------------------------------------------------------

    @Test
    void revokeIsIdempotentAndKeepsTheFirstRevocationTime() throws Exception {
        Object id = createdKey(alice, "laptop").get("id");

        String first = JsonPath.read(revoke(alice, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)))
                .andReturn().getResponse().getContentAsString(), "$.revokedAt");
        assertNotNull(first);
        revoke(alice, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt", is(first)));
    }

    @Test
    void anotherUsersKeyIsNotFoundAndStaysActive() throws Exception {
        Object id = createdKey(bob, "bobs").get("id");

        revoke(alice, id).andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND_BODY, true));
        revoke(alice, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(content().json(NOT_FOUND_BODY, true));

        list(bob).andExpect(jsonPath("$[0].active", is(true)));
    }

    @Test
    void revokeWithAMalformedIdIs400() throws Exception {
        revoke(alice, "not-a-uuid").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Filter registration
    // ------------------------------------------------------------------

    /**
     * The JWT filter is a bean, so Spring Boot would also register it as a
     * global servlet filter outside the security chain. That registration is
     * disabled; it only runs inside its chain.
     */
    @Test
    void jwtFilterIsNotRegisteredAsAGlobalServletFilter() {
        long enabled = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(r -> r.getFilter() instanceof JwtAuthenticationFilter)
                .peek(r -> assertFalse(r.isEnabled(), "JWT filter registered globally"))
                .count();
        assertEquals(1, enabled, "expected exactly one (disabled) registration for the JWT filter");
    }
}

package com.proofix.backend.runnerkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * The plaintext key exists only in the 201 response: no table in proofix_test
 * holds it (every row of every public table, cast to text, is searched), and
 * neither the key nor its hash reaches the logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PlaintextKeyNotPersistedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunnerKeyService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    private RunnerKeyTestUsers users;
    private TestUser alice;

    @BeforeEach
    void setUp() {
        users = new RunnerKeyTestUsers(jdbc, jwtService);
        alice = users.create();
    }

    @AfterEach
    void tearDown() {
        users.deleteAll();
    }

    /** Rows (in any public table) whose text form contains {@code needle}. */
    private long rowsContaining(String needle) {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);
        assertTrue(tables.contains("runner_keys"), tables.toString());
        long total = 0;
        for (String table : tables) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM public.\"" + table.replace("\"", "\"\"")
                            + "\" t WHERE strpos(t::text, ?) > 0",
                    Long.class, needle);
            total += count == null ? 0 : count;
        }
        return total;
    }

    @Test
    void plaintextKeyAppearsNowhereInTheDatabaseOrLogs(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(post("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"laptop\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.key");
        String id = JsonPath.read(body, "$.id");
        String hash = RunnerKeyCodec.hash(key);

        // Exercise every path that sees the key or its hash.
        assertTrue(service.authenticate(key).isPresent());
        mockMvc.perform(get("/api/runner-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/runner-keys").header(HttpHeaders.AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runner-keys/" + id + "/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token()))
                .andExpect(status().isOk());
        assertTrue(service.authenticate(key).isEmpty());

        // The search itself works: the hash is found, in exactly one row.
        assertEquals(1, rowsContaining(hash));
        assertEquals(0, rowsContaining(key), "plaintext key stored in the database");
        assertEquals(0, rowsContaining(key.substring(4)), "key body stored in the database");

        String logs = output.getAll();
        assertFalse(logs.contains(key.substring(4)), "key appeared in the logs");
        assertFalse(logs.contains(hash), "key hash appeared in the logs");
        assertFalse(logs.contains(alice.token()), "Authorization header appeared in the logs");
    }
}

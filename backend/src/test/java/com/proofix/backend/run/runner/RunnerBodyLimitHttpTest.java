package com.proofix.backend.run.runner;

import static com.proofix.backend.run.runner.RunnerFixtures.VERSION;
import static com.proofix.backend.run.runner.RunnerFixtures.VERSION_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.security.JwtService;

/**
 * The 64 KB cap through a real server, including bodies sent chunked
 * (no Content-Length), which only a counting read can catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RunnerBodyLimitHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RunnerKeyService keyService;

    @Autowired
    private RunStateMachine machine;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private RunnerFixtures fixtures;
    private CreatedRunnerKey key;

    @BeforeEach
    void setUp() {
        fixtures = new RunnerFixtures(jdbc, jwtService, keyService, machine);
        key = fixtures.key(fixtures.user());
    }

    @AfterEach
    void tearDown() {
        fixtures.deleteAll();
    }

    private HttpResponse<String> claim(HttpRequest.BodyPublisher body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/runner/claim"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + key.plaintext())
                .header(VERSION_HEADER, VERSION)
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** ofInputStream has an unknown length, so the client sends it chunked. */
    private static HttpRequest.BodyPublisher chunked(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
    }

    @Test
    void oversizedChunkedBodyIs413() throws Exception {
        String big = "{\"instanceId\": \"box-1\", \"pad\": \"" + "x".repeat(70_000) + "\"}";

        HttpResponse<String> response = claim(chunked(big));

        assertEquals(413, response.statusCode(), response.body());
        assertTrue(response.body().contains("Request body too large"), response.body());
    }

    @Test
    void oversizedBodyWithContentLengthIs413() throws Exception {
        String big = "{\"instanceId\": \"box-1\", \"pad\": \"" + "x".repeat(70_000) + "\"}";

        HttpResponse<String> response = claim(HttpRequest.BodyPublishers.ofString(big));

        assertEquals(413, response.statusCode(), response.body());
    }

    @Test
    void smallChunkedBodyIsAccepted() throws Exception {
        HttpResponse<String> response = claim(chunked("{\"instanceId\": \"box-1\"}"));

        assertEquals(204, response.statusCode(), response.body());
    }
}

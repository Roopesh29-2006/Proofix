package com.proofix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.proofix.runner.cli.Cli;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import com.proofix.runner.testutil.TestGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end over the quickcart-config fixture, through the real CLI. */
class QuickcartConfigFixtureTest {

    static final String YML = "api/src/main/resources/application.yml";

    @TempDir
    Path tmp;

    String canary;
    Outcome out;

    /** Copies the fixture and swaps the committed placeholder for a fresh random canary. */
    static Path load(Path tmp, String name, String canary) {
        Path repo = Fixtures.load("quickcart-config", tmp.resolve(name));
        try {
            Path yml = repo.resolve(YML);
            String s = Files.readString(yml);
            assertTrue(s.contains("__CANARY__"));
            Files.writeString(yml, s.replace("__CANARY__", canary));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return repo;
    }

    @BeforeEach
    void scan() {
        canary = "CFG-CANARY-" + UUID.randomUUID();
        out = RunnerHarness.scan(load(tmp, "a", canary), tmp.resolve("out"));
        assertEquals(0, out.exit(), out.stderr());
    }

    private JsonNode fact(String module, String name, String profile) {
        JsonNode hit = null;
        for (JsonNode f : out.json().get("facts")) {
            if (f.get("name").asText().equals(name) && module.equals(text(f.get("module")))
                    && (profile == null ? f.get("profile").isNull() : profile.equals(text(f.get("profile"))))) {
                if (hit != null) throw new AssertionError("duplicate " + name);
                hit = f;
            }
        }
        assertNotNull(hit, module + " " + name + " " + profile);
        return hit;
    }

    private JsonNode env(String name) {
        for (JsonNode f : out.json().get("facts")) {
            if (f.get("name").asText().equals("env." + name)) return f;
        }
        throw new AssertionError("no env." + name);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static List<String> values(JsonNode arr) {
        List<String> l = new ArrayList<>();
        arr.forEach(n -> l.add(text(n.get("value"))));
        return l;
    }

    private List<String> moduleValues(String module, String name) {
        List<String> l = new ArrayList<>();
        for (JsonNode f : out.json().get("facts")) {
            if (f.get("name").asText().equals(name) && module.equals(text(f.get("module")))) l.add(f.get("value").asText());
        }
        return l;
    }

    @Test
    void javaVersionConflictsWithTheDockerRuntime() {
        JsonNode j = fact("api", "java.version", null);
        assertEquals("CONFLICT", j.get("status").asText());
        assertTrue(j.get("value").isNull());
        assertEquals("runtime older than compile level", j.get("reason").asText());
        assertEquals(List.of("21", "17"), values(j.get("candidates")));
        assertTrue(values(j.get("overridden")).contains("17"), "parent java.version 17 lost to release 21");
    }

    @Test
    void withoutTheDockerfileJavaVersionIsKnown21() throws IOException {
        Path repo = load(tmp, "b", canary);
        Files.delete(repo.resolve("Dockerfile"));
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out-b"));
        JsonNode j = null;
        for (JsonNode f : o.json().get("facts")) {
            if (f.get("name").asText().equals("java.version") && f.get("module").asText().equals("api")) j = f;
        }
        assertEquals("KNOWN", j.get("status").asText());
        assertEquals("21", j.get("value").asText());
        assertTrue(values(j.get("overridden")).contains("17"));
    }

    @Test
    void mavenFacts() {
        assertEquals("3.5.0", fact("api", "spring.boot.version", null).get("value").asText());
        assertEquals("3.5.0", fact("common", "spring.boot.version", null).get("value").asText());
        JsonNode common = fact("common", "java.version", null);
        assertEquals("KNOWN", common.get("status").asText(), "parent java.version feeds Boot's release");
        assertEquals("17", common.get("value").asText());
        assertEquals(List.of("POSTGRESQL", "REDIS"), moduleValues("api", "service"));
        assertTrue(moduleValues("api", "dependency").contains("com.quickcart:common"));
        assertFalse(moduleValues("api", "dependency").contains("org.springframework.boot:spring-boot-starter-test"));
        assertEquals("api", fact("api", "app.module", null).get("value").asText());
        assertEquals("com.quickcart.api.ApiApplication", fact("api", "entry.mainClass", null).get("value").asText());
    }

    @Test
    void springFactsAndEnvVars() {
        JsonNode port = fact("api", "server.port", "default");
        assertEquals("8080", port.get("value").asText());
        assertEquals("DEFAULTED", port.get("status").asText());
        assertEquals("PORT", port.get("envVar").asText());
        assertEquals("9090", fact("api", "server.port", "prod").get("value").asText());
        assertEquals("jdbc:postgresql://localhost:5432/quickcart",
                fact("api", "spring.datasource.url", "default").get("value").asText());
        assertEquals("POSTGRESQL", fact("api", "spring.datasource.kind", "default").get("value").asText());
        assertEquals("/actuator/health", fact("api", "health.path", "default").get("value").asText());
        assertEquals("/actuator/health", fact("api", "health.effectivePath", "default").get("value").asText());

        assertEquals("8080", env("PORT").get("default").asText());
        assertTrue(env("DB_PASSWORD").get("required").asBoolean());
        assertTrue(env("DB_PASSWORD").get("default").isNull());
        assertTrue(env("STRIPE_KEY").get("required").asBoolean());
        assertEquals("localhost", env("DB_HOST").get("default").asText());
    }

    @Test
    void literalSecretIsReportedByKeyAndLineOnly() {
        JsonNode w = null;
        for (JsonNode x : out.json().get("warnings")) {
            if (x.get("code").asText().equals("LITERAL_SECRET")) w = x;
        }
        assertNotNull(w);
        assertEquals("admin.password", w.get("key").asText());
        assertEquals(YML, w.get("path").asText());
        assertEquals(11, w.get("line").asInt());
    }

    @Test
    void canaryNeverAppearsInFactsStdoutOrStderr() {
        assertFalse(out.everything().contains(canary));
        // stdout mode as well
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int exit = new Cli().run(new String[] {"scan", load(tmp, "c", canary).toString()},
                new PrintStream(so, true, StandardCharsets.UTF_8), new PrintStream(se, true, StandardCharsets.UTF_8));
        assertEquals(0, exit);
        assertFalse(so.toString(StandardCharsets.UTF_8).contains(canary));
        assertFalse(se.toString(StandardCharsets.UTF_8).contains(canary));
        assertTrue(so.toString(StandardCharsets.UTF_8).contains("\"schemaVersion\": 2"));
    }

    @Test
    void canaryNeverAppearsWhenTheYamlFailsToParse() throws IOException {
        Path repo = load(tmp, "d", canary);
        Files.writeString(repo.resolve(YML), "admin:\n  password: " + canary + "\n  broken: [\n");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out-d"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("CONFIG_UNPARSEABLE", YML));
        assertFalse(o.everything().contains(canary));
    }

    @Test
    void deterministic() {
        Outcome again = RunnerHarness.scan(load(tmp, "e", canary), tmp.resolve("out-e"));
        assertArrayEquals(out.bytes(), again.bytes());
        Outcome third = RunnerHarness.scan(load(tmp, "e2", canary), tmp.resolve("out-e2"));
        assertArrayEquals(out.bytes(), third.bytes());
    }

    @Test
    void gitModeCitesTheCommit() {
        TestGit.requireGit();
        Path repo = load(tmp, "g", canary);
        TestGit.initAndCommitAll(repo);
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out-g"));
        String sha = o.json().get("repo").get("commitSha").asText();
        assertEquals(40, sha.length());
        JsonNode ev = o.json().get("facts").get(0).get("evidence").get(0);
        assertEquals(sha, ev.get("commitSha").asText());
        assertEquals(64, ev.get("sha256").asText().length());
        assertFalse(o.everything().contains(canary));
        assertArrayEquals(o.bytes(), RunnerHarness.scan(repo, tmp.resolve("out-g2")).bytes());
    }
}

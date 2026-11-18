package com.proofix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickcartFixtureTest {

    @TempDir
    Path tmp;

    Outcome out;

    @BeforeEach
    void scan() {
        Path repo = Fixtures.load("quickcart", tmp);
        Fixtures.write(repo, ".env", "STRIPE_KEY=" + UUID.randomUUID() + "\n");
        out = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertEquals(0, out.exit(), out.stderr());
    }

    @Test
    void detectsExactlyTheExpectedStacks() {
        assertEquals(List.of("java-maven@.", "node@.", "nextjs@frontend"), out.stackIds());
    }

    @Test
    void mavenRootIsBackendWithModulesAsMembers() {
        JsonNode s = out.stack("java-maven@.");
        assertEquals("backend", s.get("role").asText());
        assertEquals(List.of("api", "common"), texts(s.get("members")));
        assertTrue(s.get("parent").isNull());
        JsonNode ev = s.get("evidence");
        assertEquals("maven", ev.get("rule").asText());
        assertTrue(ev.get("confirmed").asBoolean());
        List<String> matched = new ArrayList<>();
        ev.get("matched").forEach(m -> {
            matched.add(m.get("path").asText());
            assertEquals(64, m.get("sha256").asText().length());
            assertTrue(m.get("size").asLong() > 0);
        });
        assertEquals(List.of("api/pom.xml", "common/pom.xml", "pom.xml"), matched);
    }

    @Test
    void nextjsFrontendHasRootNodeAsParent() {
        JsonNode s = out.stack("nextjs@frontend");
        assertEquals("frontend", s.get("role").asText());
        assertEquals("node@.", s.get("parent").asText());
        assertEquals("nextjs", s.get("evidence").get("rule").asText());
        assertTrue(s.get("evidence").get("confirmed").asBoolean());
    }

    @Test
    void rootPackageJsonWithOnlyPrettierIsTooling() {
        JsonNode s = out.stack("node@.");
        assertEquals("tooling", s.get("role").asText());
        assertFalse(s.get("evidence").get("confirmed").asBoolean());
    }

    @Test
    void testTargetIsTheMavenBackend() {
        JsonNode t = out.json().get("testTarget");
        assertEquals("java-maven@.", t.get("stackId").asText());
        assertEquals("KNOWN", t.get("status").asText());
        assertEquals("SINGLE_BACKEND", t.get("reason").asText());
    }

    @Test
    void committedEnvIsReportedByPathOnly() {
        assertTrue(out.hasWarning("SECRET_FILE_PRESENT", ".env"));
    }

    @Test
    void skippedDirsAreListedAndTheirContentsIgnored() {
        assertEquals(List.of("node_modules", "target"), texts(out.json().get("scan").get("dirsSkipped")));
        // node_modules/express and target/generated contain package.json files with express:
        // if the skip list leaked, extra node stacks would appear.
        assertNotNull(out.stack("node@."));
        assertEquals(3, out.json().get("stacks").size());
    }

    @Test
    void schemaShape() {
        JsonNode j = out.json();
        assertEquals(1, j.get("schemaVersion").asInt());
        assertEquals("walk", j.get("repo").get("fileSource").asText());
        assertTrue(j.get("repo").get("commitSha").isNull());
        assertTrue(j.get("facts").isArray());
        assertEquals(0, j.get("facts").size());
        assertTrue(out.hasWarning("NOT_A_GIT_REPO", null));
        assertEquals(List.of("schemaVersion", "repo", "scan", "stacks", "testTarget", "facts", "warnings"), fields(j));
        assertEquals(List.of("id", "kind", "path", "role", "parent", "members", "evidence"),
                fields(j.get("stacks").get(0)));
    }

    static List<String> texts(JsonNode arr) {
        List<String> l = new ArrayList<>();
        arr.forEach(n -> l.add(n.asText()));
        return l;
    }

    static List<String> fields(JsonNode obj) {
        List<String> l = new ArrayList<>();
        obj.fieldNames().forEachRemaining(l::add);
        return l;
    }
}

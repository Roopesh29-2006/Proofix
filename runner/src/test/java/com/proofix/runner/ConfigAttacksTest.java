package com.proofix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.Links;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static com.proofix.runner.QuickcartConfigFixtureTest.YML;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [SEC] Hostile inputs through the real CLI: the scan always completes and nothing leaks. */
class ConfigAttacksTest {

    @TempDir
    Path tmp;

    private static String canary() {
        return "ATTACK-CANARY-" + UUID.randomUUID();
    }

    private Path repo(String name) {
        return QuickcartConfigFixtureTest.load(tmp, name, canary());
    }

    private static boolean hasFact(Outcome o, String module, String name) {
        for (JsonNode f : o.json().get("facts")) {
            if (f.get("name").asText().equals(name) && f.hasNonNull("module") && f.get("module").asText().equals(module)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void xxePomIsRejectedAndTheTargetFileNeverLeaks() throws IOException {
        String c = canary();
        Path secret = Files.writeString(tmp.resolve("outside-secret.txt"), c);
        Path r = repo("xxe");
        Files.writeString(r.resolve("api/pom.xml"), "<?xml version=\"1.0\"?>\n<!DOCTYPE project [<!ENTITY x SYSTEM \""
                + secret.toUri() + "\">]>\n<project><artifactId>&x;</artifactId></project>\n");
        Outcome o = RunnerHarness.scan(r, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("MARKER_UNPARSEABLE", "api/pom.xml"));
        assertFalse(o.everything().contains(c));
        assertFalse(hasFact(o, "api", "java.version"));
        assertTrue(hasFact(o, "common", "java.version"), "the rest of the scan continues");
    }

    @Test
    void yamlAliasBombIsBounded() throws IOException {
        Path r = repo("bomb");
        StringBuilder sb = new StringBuilder("a: &a [x,x,x,x,x,x,x,x,x]\n");
        char prev = 'a';
        for (char ch = 'b'; ch <= 'j'; ch++) {
            sb.append(ch).append(": &").append(ch).append(" [");
            for (int i = 0; i < 9; i++) sb.append(i == 0 ? "" : ",").append('*').append(prev);
            sb.append("]\n");
            prev = ch;
        }
        Files.writeString(r.resolve(YML), sb.toString());
        Outcome o = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> RunnerHarness.scan(r, tmp.resolve("out")));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("CONFIG_UNPARSEABLE", YML));
    }

    @Test
    void oversizedConfigIsSkippedByTheExistingLimit() throws IOException {
        Path r = repo("big");
        String c = canary();
        Files.writeString(r.resolve(YML), "server:\n  port: 1\nadmin:\n  password: " + c + "\n#" + "x".repeat(5000) + "\n");
        Outcome o = RunnerHarness.scan(r, tmp.resolve("out"), "--max-marker-bytes", "4000");
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("FILE_TOO_LARGE", YML));
        assertFalse(o.everything().contains(c));
        for (JsonNode f : o.json().get("facts")) {
            assertFalse(f.get("name").asText().equals("server.port") && "default".equals(f.get("profile").asText()));
        }
    }

    @Test
    void relativePathOutsideTheRepoStops() throws IOException {
        Path r = repo("outside");
        String pom = Files.readString(r.resolve("common/pom.xml"))
                .replace("</version>\n  </parent>", "</version>\n    <relativePath>../../outside</relativePath>\n  </parent>");
        Files.writeString(r.resolve("common/pom.xml"), pom);
        Outcome o = RunnerHarness.scan(r, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("PARENT_OUTSIDE_REPO", "common/pom.xml"));
    }

    @Test
    void malformedXmlAndYamlWarnAndTheScanContinues() throws IOException {
        Path r = repo("malformed");
        Files.writeString(r.resolve("common/pom.xml"), "<project><artifactId>common</b></project>");
        Files.writeString(r.resolve("api/src/main/resources/application-prod.yml"), "server: {port: [\n");
        Outcome o = RunnerHarness.scan(r, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("MARKER_UNPARSEABLE", "common/pom.xml"));
        assertTrue(o.hasWarning("CONFIG_UNPARSEABLE", "api/src/main/resources/application-prod.yml"));
        assertTrue(hasFact(o, "api", "server.port"), "application.yml still read");
    }

    @Test
    void symlinkedApplicationYmlIsSkipped() throws IOException {
        Path r = repo("link");
        String c = canary();
        Path target = Files.writeString(tmp.resolve("elsewhere.yml"), "admin:\n  password: " + c + "\nserver:\n  port: 1\n");
        Files.delete(r.resolve(YML));
        Links.symlinkOrSkip(r.resolve(YML), target);
        Outcome o = RunnerHarness.scan(r, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("SYMLINK_SKIPPED", YML));
        assertFalse(o.everything().contains(c));
    }
}

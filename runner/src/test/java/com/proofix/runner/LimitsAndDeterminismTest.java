package com.proofix.runner;

import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import com.proofix.runner.testutil.TestGit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitsAndDeterminismTest {

    @TempDir
    Path tmp;

    @Test
    void depthLimitHit() {
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "go.mod", "module x\n");
        Fixtures.write(repo, "a/b/c/deep.txt", "x");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"), "--max-depth", "1");
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("DEPTH_LIMIT_HIT", null));
        assertTrue(o.json().get("scan").get("limitsHit").toString().contains("DEPTH_LIMIT_HIT"));
        assertEquals(1, o.json().get("scan").get("filesSeen").asInt());
    }

    @Test
    void depthLimitHitInGitMode() {
        TestGit.requireGit();
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "go.mod", "module x\n");
        Fixtures.write(repo, "a/b/c/deep.txt", "x");
        TestGit.initAndCommitAll(repo);
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"), "--max-depth", "2");
        assertEquals("git", o.json().get("repo").get("fileSource").asText());
        assertTrue(o.hasWarning("DEPTH_LIMIT_HIT", "a/b/c/deep.txt"));
    }

    @Test
    void fileLimitHit() {
        Path repo = tmp.resolve("repo");
        for (String f : List.of("a.txt", "b.txt", "c.txt")) Fixtures.write(repo, f, "x");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"), "--max-files", "2");
        assertEquals(0, o.exit());
        assertEquals(2, o.json().get("scan").get("filesSeen").asInt());
        assertTrue(o.hasWarning("FILE_LIMIT_HIT", "c.txt"));
    }

    @Test
    void markerSizeLimitHit() {
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "package.json", "{\"dependencies\":{\"express\":\"4\"}}");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"), "--max-marker-bytes", "10");
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("FILE_TOO_LARGE", "package.json"));
        assertTrue(o.stack("node@.").get("evidence").get("matched").get(0).get("sha256").isNull());
        List<String> limits = new ArrayList<>();
        o.json().get("scan").get("limitsHit").forEach(n -> limits.add(n.asText()));
        assertEquals(List.of("FILE_TOO_LARGE"), limits);
    }

    @Test
    void sameFixtureTwiceIsByteIdentical() {
        Path a = Fixtures.load("quickcart", tmp.resolve("one"));
        Path b = Fixtures.load("quickcart", tmp.resolve("two"));
        Outcome first = RunnerHarness.scan(a, tmp.resolve("out"));
        Outcome again = RunnerHarness.scan(a, tmp.resolve("out"));
        Outcome other = RunnerHarness.scan(b, tmp.resolve("out"));
        assertArrayEquals(first.bytes(), again.bytes());
        assertArrayEquals(first.bytes(), other.bytes(), "output must not depend on the absolute root path");
        String json = new String(first.bytes(), StandardCharsets.UTF_8);
        assertFalse(json.contains("\r"), "LF line endings only");
        assertTrue(json.endsWith("}\n"));
        assertFalse(json.contains(tmp.toString().replace("\\", "\\\\")), "no absolute paths");
    }

    @Test
    void gitModeTwiceIsByteIdentical() {
        TestGit.requireGit();
        Path repo = Fixtures.load("quickcart", tmp);
        TestGit.initAndCommitAll(repo);
        Outcome first = RunnerHarness.scan(repo, tmp.resolve("out"));
        Outcome again = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertEquals("git", first.json().get("repo").get("fileSource").asText());
        assertArrayEquals(first.bytes(), again.bytes());
    }
}

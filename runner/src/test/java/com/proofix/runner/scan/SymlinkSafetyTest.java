package com.proofix.runner.scan;

import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.Links;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [SEC] Links that point outside the repository are never followed in walk mode. The outside
 * directory holds a package.json with a server dependency and a canary: if a link were followed,
 * a stack or the canary would show up in facts.json.
 */
class SymlinkSafetyTest {

    @TempDir
    Path tmp;

    private Path outside(String canary) {
        Path out = tmp.resolve("outside");
        Fixtures.write(out, "package.json",
                "{\"name\":\"" + canary + "\",\"scripts\":{\"start\":\"x\"},\"dependencies\":{\"express\":\"4\"}}");
        return out;
    }

    private void assertNotFollowed(Outcome o, String linkName, String canary) {
        assertEquals(0, o.exit(), o.stderr());
        assertEquals(0, o.json().get("stacks").size(), "linked package.json must not create a stack");
        assertFalse(o.everything().contains(canary));
        assertFalse(new String(o.bytes()).contains(linkName + "/package.json"));
        assertTrue(o.hasWarning("SYMLINK_SKIPPED", linkName));
    }

    @Test
    void directorySymlinkToOutsideIsNotFollowed() {
        String canary = "canary-" + UUID.randomUUID();
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "README.md", "x");
        Links.symlinkOrSkip(repo.resolve("linked"), outside(canary));
        assertNotFollowed(RunnerHarness.scan(repo, tmp.resolve("out")), "linked", canary);
    }

    @Test
    void fileSymlinkToOutsideIsNotFollowed() {
        String canary = "canary-" + UUID.randomUUID();
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "README.md", "x");
        Links.symlinkOrSkip(repo.resolve("package.json"), outside(canary).resolve("package.json"));
        assertNotFollowed(RunnerHarness.scan(repo, tmp.resolve("out")), "package.json", canary);
    }

    @Test
    void junctionToOutsideIsNotFollowed() {
        String canary = "canary-" + UUID.randomUUID();
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "README.md", "x");
        Links.junctionOrSkip(repo.resolve("linked"), outside(canary));
        assertNotFollowed(RunnerHarness.scan(repo, tmp.resolve("out")), "linked", canary);
    }

    @Test
    void junctionLoopBackIntoRepoIsNotFollowed() {
        Path repo = tmp.resolve("repo");
        Fixtures.write(repo, "go.mod", "module x\n");
        Links.junctionOrSkip(repo.resolve("loop"), repo);
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertEquals(0, o.exit(), o.stderr());
        assertEquals(1, o.json().get("stacks").size());
        assertEquals(1, o.json().get("scan").get("filesSeen").asInt());
        assertTrue(o.hasWarning("SYMLINK_SKIPPED", "loop"));
    }
}

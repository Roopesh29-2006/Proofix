package com.proofix.runner.cli;

import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path tmp;

    private int run(ByteArrayOutputStream so, String... args) {
        return new Cli().run(args, new PrintStream(so, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void successWithWarningsExitsZero() {
        RunnerHarness.Outcome o = RunnerHarness.scan(Fixtures.load("quickcart", tmp), tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.json().get("warnings").size() > 0);
    }

    @Test
    void writesToStdoutWithoutOut() {
        Path repo = Fixtures.load("express-api", tmp);
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        assertEquals(0, run(so, "scan", repo.toString()));
        assertTrue(so.toString(StandardCharsets.UTF_8).contains("\"schemaVersion\": 1"));
    }

    @Test
    void missingRepoPathIsNonZero() {
        assertEquals(Cli.REPO_UNREADABLE, run(new ByteArrayOutputStream(), "scan", tmp.resolve("nope").toString()));
    }

    @Test
    void fileInsteadOfDirectoryIsNonZero() throws Exception {
        Path f = Files.writeString(tmp.resolve("file.txt"), "x");
        assertEquals(Cli.REPO_UNREADABLE, run(new ByteArrayOutputStream(), "scan", f.toString()));
    }

    @Test
    void unwritableOutputIsNonZero() throws Exception {
        Path repo = Fixtures.load("express-api", tmp);
        Path dirAsOut = Files.createDirectories(tmp.resolve("is-a-dir"));
        assertEquals(Cli.OUTPUT_UNWRITABLE,
                run(new ByteArrayOutputStream(), "scan", repo.toString(), "--out", dirAsOut.toString()));
        assertEquals(Cli.OUTPUT_UNWRITABLE, run(new ByteArrayOutputStream(), "scan", repo.toString(),
                "--out", tmp.resolve("missing-dir/facts.json").toString()));
    }

    @Test
    void usageErrors() {
        assertEquals(Cli.USAGE_ERROR, run(new ByteArrayOutputStream()));
        assertEquals(Cli.USAGE_ERROR, run(new ByteArrayOutputStream(), "analyze", "."));
        assertEquals(Cli.USAGE_ERROR, run(new ByteArrayOutputStream(), "scan", ".", "--max-files", "abc"));
        assertEquals(Cli.USAGE_ERROR, run(new ByteArrayOutputStream(), "scan", ".", "--max-files", "0"));
        assertEquals(Cli.USAGE_ERROR, run(new ByteArrayOutputStream(), "scan", ".", "--bogus"));
    }
}

package com.proofix.runner.testutil;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-only git helper for building temporary repositories (init/add/commit). Production code
 * never runs these commands; it is limited to GitProcess.Command.
 */
public final class TestGit {
    private TestGit() {}

    public static boolean available() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void requireGit() {
        Assumptions.assumeTrue(available(), "SKIPPED: git not installed");
    }

    public static String git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git",
                "-c", "user.name=runner-test", "-c", "user.email=runner-test@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static void initAndCommitAll(Path dir) {
        git(dir, "init", "-q");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "fixture");
    }
}

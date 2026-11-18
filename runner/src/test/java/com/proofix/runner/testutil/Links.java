package com.proofix.runner.testutil;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Creates links for safety tests. Real symlinks need admin or Developer Mode on Windows; when
 * they are not permitted the test is aborted (reported as skipped) with the reason. Directory
 * links fall back to NTFS junctions on Windows, which need no privilege.
 */
public final class Links {
    private Links() {}

    public static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** Creates a real symlink or aborts the test, reporting why. */
    public static void symlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("SKIPPED: symlink creation not permitted on this machine ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    /** Directory symlink, or a junction on Windows when symlinks are not permitted. */
    public static void dirLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            if (!WINDOWS) Assumptions.abort("SKIPPED: directory symlink not permitted: " + e.getMessage());
        }
        junctionOrSkip(link, target);
    }

    /** NTFS junction (Windows only). */
    public static void junctionOrSkip(Path link, Path target) {
        if (!WINDOWS) Assumptions.abort("SKIPPED: junctions exist only on Windows");
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.exists(link)) {
                Assumptions.abort("SKIPPED: junction creation failed");
            }
        } catch (IOException e) {
            Assumptions.abort("SKIPPED: junction creation failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assumptions.abort("interrupted");
        }
    }
}

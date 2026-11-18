package com.proofix.runner.scan;

import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.Links;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [SEC] Every path from a file source goes through PathGuard, whatever the source claims. */
class PathGuardTest {

    @TempDir
    Path tmp;

    private static ScanResult scanListing(Path root, List<String> paths) throws Exception {
        FileSource injected = r -> new FileSource.Listing("walk", paths, List.of(), List.of(), List.of());
        return new Scanner(Limits.DEFAULTS, injected, null, null).scan(root);
    }

    private static List<String> paths(ScanResult r) {
        return r.files().stream().map(ScannedFile::path).toList();
    }

    private static boolean warned(ScanResult r, String code, String path) {
        return r.warnings().stream().anyMatch(w -> w.code().equals(code) && path.equals(w.path()));
    }

    @Test
    void dotDotAbsoluteAndDriveLetterPathsAreDropped() throws Exception {
        Path root = tmp.resolve("repo");
        Fixtures.write(root, "ok.txt", "ok");
        Fixtures.write(tmp, "x", "outside");
        String absInside = root.resolve("ok.txt").toAbsolutePath().toString();
        ScanResult r = scanListing(root, List.of(
                "../x", "a/../../x", "..\\x", "/etc/passwd", "C:/Windows/win.ini", "c:\\x", absInside, "ok.txt"));
        assertEquals(List.of("ok.txt"), paths(r));
        for (String p : List.of("../x", "a/../../x", "..\\x", "/etc/passwd", "C:/Windows/win.ini", "c:\\x")) {
            assertTrue(warned(r, WarningCodes.PATH_OUTSIDE_ROOT, p), "expected PATH_OUTSIDE_ROOT for " + p);
        }
        assertTrue(r.warnings().stream().anyMatch(w -> w.code().equals(WarningCodes.PATH_OUTSIDE_ROOT)
                && w.path().contains("ok.txt")), "absolute path is rejected even when it points inside");
    }

    @Test
    void directoryEntriesAreSubmodulesAndMissingFilesAreSilent() throws Exception {
        Path root = tmp.resolve("repo");
        Files.createDirectories(root.resolve("libs/sub"));
        Fixtures.write(root, "a.txt", "a");
        ScanResult r = scanListing(root, List.of("libs/sub", "deleted.txt", "a.txt", "./a.txt"));
        assertEquals(List.of("a.txt"), paths(r));
        assertTrue(warned(r, WarningCodes.SUBMODULE_SKIPPED, "libs/sub"));
        assertFalse(r.warnings().stream().map(Warning::path).anyMatch("deleted.txt"::equals));
    }

    @Test
    void fileSymlinkInListingIsDropped() throws Exception {
        Path root = tmp.resolve("repo");
        Fixtures.write(root, "a.txt", "a");
        Fixtures.write(tmp, "outside/secret.txt", "s");
        Links.symlinkOrSkip(root.resolve("link.txt"), tmp.resolve("outside/secret.txt"));
        ScanResult r = scanListing(root, List.of("a.txt", "link.txt"));
        assertEquals(List.of("a.txt"), paths(r));
        assertTrue(warned(r, WarningCodes.SYMLINK_SKIPPED, "link.txt"));
    }

    @Test
    void linkedDirectoryAndPathsThroughItAreDropped() throws Exception {
        Path root = tmp.resolve("repo");
        Fixtures.write(root, "a.txt", "a");
        Fixtures.write(tmp, "outside/package.json", "{}");
        Links.dirLinkOrSkip(root.resolve("linked"), tmp.resolve("outside"));
        ScanResult r = scanListing(root, List.of("a.txt", "linked", "linked/package.json"));
        assertEquals(List.of("a.txt"), paths(r));
        assertTrue(warned(r, WarningCodes.SYMLINK_SKIPPED, "linked/package.json"));
        // "linked" itself is a link, not a submodule
        assertTrue(r.warnings().stream().noneMatch(w -> w.code().equals(WarningCodes.SUBMODULE_SKIPPED)));
    }

    @Test
    void lexicalNormalization() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        PathGuard g = new PathGuard(root);
        assertEquals("a/b.txt", g.lexical("./a//b.txt"));
        assertEquals("a/b.txt", g.lexical("a\\b.txt"));
        assertEquals(null, g.lexical(""));
        assertEquals(null, g.lexical("."));
        assertEquals(null, g.lexical("a/\0b"));
    }
}

package com.proofix.runner.scan;

import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.TestGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitModeTest {

    @TempDir
    Path tmp;

    Path repo;

    @BeforeEach
    void repo() {
        TestGit.requireGit();
        repo = tmp.resolve("repo");
        Fixtures.write(repo, ".gitignore", "generated/\n");
        Fixtures.write(repo, "package.json", "{\"scripts\":{\"start\":\"node i.js\"},\"dependencies\":{\"express\":\"4\"}}");
        Fixtures.write(repo, "src/index.js", "x\n");
        Fixtures.write(repo, "generated/out.js", "ignored\n");
        TestGit.initAndCommitAll(repo);
    }

    private static List<String> paths(ScanResult r) {
        return r.files().stream().map(ScannedFile::path).toList();
    }

    private static boolean has(ScanResult r, String code) {
        return r.warnings().stream().map(Warning::code).anyMatch(code::equals);
    }

    @Test
    void cleanRepoUsesGitAndRespectsGitignore() throws Exception {
        ScanResult r = Scanner.forRepo(Limits.DEFAULTS).scan(repo);
        assertEquals("git", r.fileSource());
        assertEquals(List.of(".gitignore", "package.json", "src/index.js"), paths(r));
        assertNotNull(r.git().commitSha());
        assertEquals(40, r.git().commitSha().length());
        assertFalse(r.git().dirty());
        assertFalse(has(r, "UNCOMMITTED_CHANGES"));
    }

    @Test
    void untrackedFileIsIncludedAndMarksDirty() throws Exception {
        Fixtures.write(repo, "src/new.js", "new\n");
        ScanResult r = Scanner.forRepo(Limits.DEFAULTS).scan(repo);
        assertTrue(paths(r).contains("src/new.js"));
        assertTrue(r.git().dirty());
        assertTrue(has(r, "UNCOMMITTED_CHANGES"));
    }

    @Test
    void modifiedTrackedFileMarksDirty() throws Exception {
        Fixtures.write(repo, "src/index.js", "changed\n");
        ScanResult r = Scanner.forRepo(Limits.DEFAULTS).scan(repo);
        assertTrue(r.git().dirty());
    }

    @Test
    void deletedTrackedFileIsSilentlyDropped() throws Exception {
        java.nio.file.Files.delete(repo.resolve("src/index.js"));
        ScanResult r = Scanner.forRepo(Limits.DEFAULTS).scan(repo);
        assertFalse(paths(r).contains("src/index.js"));
        assertTrue(r.warnings().stream().noneMatch(w -> "src/index.js".equals(w.path())));
    }

    @Test
    void committedJunkIsStillSkipped() throws Exception {
        Fixtures.write(repo, "node_modules/x/package.json", "{}");
        TestGit.git(repo, "add", "-f", "node_modules");
        TestGit.git(repo, "commit", "-q", "-m", "junk");
        ScanResult r = Scanner.forRepo(Limits.DEFAULTS).scan(repo);
        assertEquals("git", r.fileSource());
        assertEquals(List.of("node_modules"), r.skippedDirs());
        assertFalse(paths(r).contains("node_modules/x/package.json"));
    }

    @Test
    void nonGitDirFallsBackToWalk() throws Exception {
        Path plain = tmp.resolve("plain");
        Fixtures.write(plain, "go.mod", "module x\n");
        ScanResult r = Scanner.forRepo(Limits.DEFAULTS).scan(plain);
        assertEquals("walk", r.fileSource());
        assertTrue(has(r, "NOT_A_GIT_REPO"));
        assertNull(r.git().commitSha());
        assertEquals(List.of("go.mod"), paths(r));
    }

    @Test
    void missingGitFallsBackToWalk() throws Exception {
        GitProcess bogus = new GitProcess("git-executable-that-does-not-exist-" + System.nanoTime());
        Scanner s = new Scanner(Limits.DEFAULTS, new GitFileSource(bogus), new WalkFileSource(Limits.DEFAULTS), bogus);
        ScanResult r = s.scan(repo);
        assertEquals("walk", r.fileSource());
        assertTrue(has(r, "GIT_UNAVAILABLE"));
        // walk mode does not know about .gitignore, but it never enters .git
        assertTrue(paths(r).contains("generated/out.js"));
        assertTrue(r.skippedDirs().contains(".git"));
    }
}

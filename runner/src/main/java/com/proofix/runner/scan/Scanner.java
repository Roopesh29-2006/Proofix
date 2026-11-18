package com.proofix.runner.scan;

import com.proofix.runner.scan.model.GitInfo;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Produces a {@link ScanResult}: a sorted list of regular files inside the root, plus git info.
 * Reads no file contents.
 */
public final class Scanner {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40,64}");

    private final Limits limits;
    private final FileSource primary;
    private final FileSource fallback;
    private final GitProcess git; // null: never query git info

    public Scanner(Limits limits, FileSource primary, FileSource fallback, GitProcess git) {
        this.limits = limits;
        this.primary = primary;
        this.fallback = fallback;
        this.git = git;
    }

    /** Default wiring: git ls-files, falling back to a directory walk. */
    public static Scanner forRepo(Limits limits) {
        GitProcess git = new GitProcess();
        return new Scanner(limits, new GitFileSource(git), new WalkFileSource(limits), git);
    }

    public ScanResult scan(Path repoPath) throws IOException {
        Path root = repoPath.toAbsolutePath().normalize();
        List<Warning> warnings = new ArrayList<>();

        FileSource.Listing listing;
        try {
            listing = primary.list(root);
        } catch (FileSource.FileSourceException e) {
            warnings.add(new Warning(e.code(), null, e.getMessage()));
            if (fallback == null) throw new IOException("no file source available");
            try {
                listing = fallback.list(root);
            } catch (FileSource.FileSourceException e2) {
                throw new IOException("directory walk failed");
            }
        }
        warnings.addAll(listing.warnings());

        PathGuard guard = new PathGuard(root);
        TreeSet<String> skipped = new TreeSet<>(listing.skippedDirs());
        TreeSet<String> depthOffenders = new TreeSet<>(listing.depthPruned());
        TreeSet<String> candidates = new TreeSet<>();

        for (String raw : listing.paths()) {
            String rel = guard.lexical(raw);
            if (rel == null) {
                if (raw != null && !raw.isEmpty()) {
                    warnings.add(new Warning(WarningCodes.PATH_OUTSIDE_ROOT, safeDisplay(raw),
                            "path outside the repository root dropped"));
                }
                continue;
            }
            String skippedPrefix = SkipList.skippedPrefix(rel);
            if (skippedPrefix != null) {
                skipped.add(skippedPrefix);
                continue;
            }
            int dirDepth = rel.split("/").length - 1;
            if (dirDepth > limits.maxDepth()) {
                depthOffenders.add(rel);
                continue;
            }
            candidates.add(rel);
        }

        if (!depthOffenders.isEmpty()) {
            warnings.add(new Warning(WarningCodes.DEPTH_LIMIT_HIT, depthOffenders.first(),
                    "paths deeper than " + limits.maxDepth() + " directories skipped: " + depthOffenders.size()));
        }

        List<ScannedFile> files = new ArrayList<>();
        int remaining = candidates.size();
        for (String rel : candidates) {
            if (files.size() >= limits.maxFiles()) {
                warnings.add(new Warning(WarningCodes.FILE_LIMIT_HIT, rel,
                        "file limit " + limits.maxFiles() + " reached; candidates not scanned: " + remaining));
                break;
            }
            remaining--;
            PathGuard.Verdict v = guard.check(rel);
            if (v.file() != null) files.add(v.file());
            else if (v.warning() != null) warnings.add(v.warning());
        }

        GitInfo gitInfo = GitInfo.NONE;
        if ("git".equals(listing.source()) && git != null) {
            gitInfo = readGitInfo(root, warnings);
        }

        warnings.sort(Warning.ORDER);
        return new ScanResult(root, files, new ArrayList<>(skipped), gitInfo, listing.source(), dedupe(warnings));
    }

    private GitInfo readGitInfo(Path root, List<Warning> warnings) {
        String sha = null;
        try {
            GitProcess.Result r = git.run(root, GitProcess.Command.REV_PARSE_HEAD);
            String out = new String(r.stdout(), StandardCharsets.UTF_8).trim();
            if (r.exitCode() == 0 && SHA.matcher(out).matches()) sha = out;
        } catch (GitProcess.GitException e) {
            warnings.add(new Warning(WarningCodes.GIT_UNAVAILABLE, null, "git rev-parse failed"));
        }
        boolean dirty = false;
        try {
            GitProcess.Result r = git.run(root, GitProcess.Command.STATUS_PORCELAIN);
            if (r.exitCode() == 0) {
                dirty = r.stdout().length > 0;
            } else {
                warnings.add(new Warning(WarningCodes.GIT_UNAVAILABLE, null, "git status failed"));
            }
        } catch (GitProcess.GitException e) {
            warnings.add(new Warning(WarningCodes.GIT_UNAVAILABLE, null, "git status failed"));
        }
        if (dirty) {
            warnings.add(new Warning(WarningCodes.UNCOMMITTED_CHANGES, null,
                    "working tree has uncommitted changes; analyzing it as-is"));
        }
        return new GitInfo(sha, dirty);
    }

    /** Rejected paths are echoed for diagnosis; strip control characters and cap the length. */
    private static String safeDisplay(String raw) {
        String s = raw.replaceAll("\\p{Cntrl}", "?");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static List<Warning> dedupe(List<Warning> sorted) {
        List<Warning> out = new ArrayList<>();
        for (Warning w : sorted) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(w)) out.add(w);
        }
        return out;
    }
}

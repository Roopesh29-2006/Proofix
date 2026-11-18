package com.proofix.runner.scan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Primary file source: {@code git ls-files --cached --others --exclude-standard -z}. */
public final class GitFileSource implements FileSource {

    private final GitProcess git;

    public GitFileSource(GitProcess git) {
        this.git = git;
    }

    @Override
    public Listing list(Path root) throws FileSourceException {
        GitProcess.Result r;
        try {
            r = git.run(root, GitProcess.Command.LS_FILES);
        } catch (GitProcess.GitException e) {
            throw new FileSourceException(WarningCodes.GIT_UNAVAILABLE, "git unavailable; using directory walk");
        }
        if (r.exitCode() != 0) {
            if (r.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new FileSourceException(WarningCodes.NOT_A_GIT_REPO, "not a git repository; using directory walk");
            }
            // e.g. "dubious ownership" or a corrupt repository
            throw new FileSourceException(WarningCodes.GIT_UNAVAILABLE, "git ls-files failed; using directory walk");
        }
        String all = new String(r.stdout(), StandardCharsets.UTF_8);
        List<String> paths = new ArrayList<>();
        for (String s : all.split("\0")) {
            if (!s.isEmpty()) paths.add(s);
        }
        return new Listing("git", paths, List.of(), List.of(), List.of());
    }
}

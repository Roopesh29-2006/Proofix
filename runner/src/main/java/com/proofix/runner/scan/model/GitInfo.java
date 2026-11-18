package com.proofix.runner.scan.model;

/** commitSha is null when not in git mode or the repository has no commits. */
public record GitInfo(String commitSha, boolean dirty) {
    public static final GitInfo NONE = new GitInfo(null, false);
}

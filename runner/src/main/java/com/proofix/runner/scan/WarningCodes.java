package com.proofix.runner.scan;

/** All warning codes the runner can emit. */
public final class WarningCodes {
    private WarningCodes() {}

    public static final String GIT_UNAVAILABLE = "GIT_UNAVAILABLE";
    public static final String NOT_A_GIT_REPO = "NOT_A_GIT_REPO";
    public static final String UNCOMMITTED_CHANGES = "UNCOMMITTED_CHANGES";

    public static final String PATH_OUTSIDE_ROOT = "PATH_OUTSIDE_ROOT";
    public static final String SYMLINK_SKIPPED = "SYMLINK_SKIPPED";
    public static final String SUBMODULE_SKIPPED = "SUBMODULE_SKIPPED";
    public static final String SPECIAL_FILE_SKIPPED = "SPECIAL_FILE_SKIPPED";
    public static final String PATH_UNREADABLE = "PATH_UNREADABLE";

    public static final String DEPTH_LIMIT_HIT = "DEPTH_LIMIT_HIT";
    public static final String FILE_LIMIT_HIT = "FILE_LIMIT_HIT";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";

    public static final String MARKER_UNPARSEABLE = "MARKER_UNPARSEABLE";
    public static final String DOCKERFILE_ONLY = "DOCKERFILE_ONLY";
    public static final String SECRET_FILE_PRESENT = "SECRET_FILE_PRESENT";

    public static final String PARENT_OUTSIDE_REPO = "PARENT_OUTSIDE_REPO";
    public static final String PARENT_NOT_FOUND = "PARENT_NOT_FOUND";
    public static final String CONFIG_UNPARSEABLE = "CONFIG_UNPARSEABLE";
    public static final String LITERAL_SECRET = "LITERAL_SECRET";
}

package com.proofix.runner.scan;

/**
 * Scan limits. maxDepth counts directory levels below the root (a file in the root has depth 0).
 */
public record Limits(int maxDepth, int maxFiles, long maxMarkerBytes) {

    public static final int DEFAULT_MAX_DEPTH = 12;
    public static final int DEFAULT_MAX_FILES = 20_000;
    public static final long DEFAULT_MAX_MARKER_BYTES = 2L * 1024 * 1024;

    public static final Limits DEFAULTS =
            new Limits(DEFAULT_MAX_DEPTH, DEFAULT_MAX_FILES, DEFAULT_MAX_MARKER_BYTES);

    public Limits {
        if (maxDepth < 0 || maxFiles < 1 || maxMarkerBytes < 0) {
            throw new IllegalArgumentException("invalid limits");
        }
    }
}

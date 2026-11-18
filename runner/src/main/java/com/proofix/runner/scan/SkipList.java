package com.proofix.runner.scan;

import java.util.Set;

/** Directory names that are never scanned, in either file-source mode. */
public final class SkipList {
    private SkipList() {}

    public static final Set<String> DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out", ".next", ".nuxt", "vendor",
            ".venv", "venv", "__pycache__", ".gradle", ".idea", ".vscode", "coverage");

    public static boolean isSkipped(String dirName) {
        return DIRS.contains(dirName);
    }

    /**
     * Returns the relative directory prefix of the first skipped segment of a file path
     * (e.g. "frontend/node_modules"), or null if no directory segment is skipped.
     */
    public static String skippedPrefix(String relativeFilePath) {
        String[] segs = relativeFilePath.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segs.length - 1; i++) {
            if (i > 0) prefix.append('/');
            prefix.append(segs[i]);
            if (isSkipped(segs[i])) return prefix.toString();
        }
        return null;
    }
}

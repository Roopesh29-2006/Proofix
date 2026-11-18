package com.proofix.runner.cli;

import com.proofix.runner.scan.Limits;

import java.nio.file.Path;

/** Parsed command line for {@code scan <repoPath> [--out f] [--max-depth n] [--max-files n] [--max-marker-bytes n]}. */
public record CliOptions(Path repoPath, Path out, Limits limits) {

    public static final String USAGE =
            "usage: devbuddy-runner scan <repoPath> [--out facts.json] [--max-depth N] [--max-files N] [--max-marker-bytes N]";

    public static final class UsageException extends Exception {
        public UsageException(String message) {
            super(message);
        }
    }

    public static CliOptions parse(String[] args) throws UsageException {
        if (args.length < 2 || !"scan".equals(args[0])) throw new UsageException(USAGE);
        Path repo = null;
        Path out = null;
        int maxDepth = Limits.DEFAULT_MAX_DEPTH;
        int maxFiles = Limits.DEFAULT_MAX_FILES;
        long maxMarker = Limits.DEFAULT_MAX_MARKER_BYTES;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--max-depth" -> maxDepth = (int) number(value(args, ++i, a), a);
                case "--max-files" -> maxFiles = (int) number(value(args, ++i, a), a);
                case "--max-marker-bytes" -> maxMarker = number(value(args, ++i, a), a);
                default -> {
                    if (a.startsWith("--") || repo != null) throw new UsageException("unexpected argument: " + a);
                    repo = Path.of(a);
                }
            }
        }
        if (repo == null) throw new UsageException(USAGE);
        try {
            return new CliOptions(repo, out, new Limits(maxDepth, maxFiles, maxMarker));
        } catch (IllegalArgumentException e) {
            throw new UsageException("invalid limit value");
        }
    }

    private static String value(String[] args, int i, String flag) throws UsageException {
        if (i >= args.length) throw new UsageException("missing value for " + flag);
        return args[i];
    }

    private static long number(String s, String flag) throws UsageException {
        try {
            long n = Long.parseLong(s);
            if (n < 0 || n > Integer.MAX_VALUE) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new UsageException("invalid number for " + flag);
        }
    }
}

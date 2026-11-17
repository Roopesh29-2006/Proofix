package com.proofix.backend.security;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A runner version, strictly MAJOR.MINOR.PATCH with 1-4 digits each (no
 * prefix, suffix or whitespace). Compared numerically, so 1.10.0 > 1.9.0.
 */
public record RunnerVersion(int major, int minor, int patch) implements Comparable<RunnerVersion> {

    private static final Pattern SHAPE = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");

    public static Optional<RunnerVersion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher m = SHAPE.matcher(value);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new RunnerVersion(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))));
    }

    /** For configuration: fails at startup on a malformed value. */
    public static RunnerVersion require(String value, String property) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException(
                property + " must be MAJOR.MINOR.PATCH"));
    }

    @Override
    public int compareTo(RunnerVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}

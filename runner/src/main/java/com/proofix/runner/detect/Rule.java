package com.proofix.runner.detect;

import java.util.List;

/**
 * A detection rule. {@code requires} is a list of groups; every group must be satisfied, and a
 * group is satisfied by any one of its patterns. A pattern is an exact file name or a name with a
 * single '*' that stands for one or more characters ("*.csproj", "application-*.yml").
 */
public record Rule(String id, String ecosystem, String kind, List<List<String>> requires) {

    public Rule {
        requires = requires.stream().map(List::copyOf).toList();
    }

    public static boolean matches(String pattern, String fileName) {
        int star = pattern.indexOf('*');
        if (star < 0) return pattern.equals(fileName);
        if (pattern.indexOf('*', star + 1) >= 0) {
            throw new IllegalArgumentException("pattern may contain at most one '*'");
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        return fileName.length() > prefix.length() + suffix.length()
                && fileName.startsWith(prefix)
                && fileName.endsWith(suffix);
    }

    public boolean isMarker(String fileName) {
        for (List<String> group : requires) {
            for (String p : group) {
                if (matches(p, fileName)) return true;
            }
        }
        return false;
    }

    /** True if every group is satisfied by at least one of the given file names. */
    public boolean matchesDir(List<String> fileNames) {
        for (List<String> group : requires) {
            boolean ok = false;
            for (String p : group) {
                for (String f : fileNames) {
                    if (matches(p, f)) {
                        ok = true;
                        break;
                    }
                }
                if (ok) break;
            }
            if (!ok) return false;
        }
        return true;
    }
}

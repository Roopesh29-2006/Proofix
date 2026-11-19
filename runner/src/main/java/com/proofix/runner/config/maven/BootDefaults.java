package com.proofix.runner.config.maven;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DATA: the properties spring-boot-starter-parent declares that decide the Java level, keyed by
 * Boot major.minor. The parent is modelled as the lowest-priority property layer, so a child that
 * sets java.version still feeds the parent's ${java.version} reference, and a parent release beats
 * a child source/target, exactly as in a real build.
 *
 * <p>Verified 2026-11-19 against the starter-parent POMs in the local ~/.m2: 3.0.5 sets
 * java.version 17 with maven.compiler.source/target = ${java.version}; 3.2.5, 3.5.0, 4.0.2, 4.0.6,
 * 4.1.0 and 4.1.2 set java.version 17 with maven.compiler.release = ${java.version}. 3.3 and 3.4
 * are included as part of the approved 3.2-3.5 range. Boot 2.x and 3.1 were not available to
 * verify and are deliberately absent: they resolve to UNRESOLVED.
 */
public final class BootDefaults {
    private BootDefaults() {}

    private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d+)\\.(\\d+)(?:[.\\-].*)?$");

    private static final Map<String, String> SOURCE_TARGET = layer(false);
    private static final Map<String, String> RELEASE = layer(true);

    private static final Map<String, Map<String, String>> TABLE = Map.of(
            "3.0", SOURCE_TARGET,
            "3.2", RELEASE,
            "3.3", RELEASE,
            "3.4", RELEASE,
            "3.5", RELEASE,
            "4.0", RELEASE,
            "4.1", RELEASE);

    private static Map<String, String> layer(boolean release) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("java.version", "17");
        if (release) {
            m.put("maven.compiler.release", "${java.version}");
        } else {
            m.put("maven.compiler.source", "${java.version}");
            m.put("maven.compiler.target", "${java.version}");
        }
        return Map.copyOf(m);
    }

    /** The parent's property layer for this Boot version, or null when not verified. */
    public static Map<String, String> properties(String bootVersion) {
        if (bootVersion == null) return null;
        Matcher m = MAJOR_MINOR.matcher(bootVersion.trim());
        if (!m.matches()) return null;
        return TABLE.get(Integer.parseInt(m.group(1)) + "." + Integer.parseInt(m.group(2)));
    }
}

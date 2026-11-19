package com.proofix.runner.config.jdk;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a .java-version file (jenv / asdf / SDKMAN style): the first non-blank, non-comment line,
 * e.g. "17", "17.0.2", "1.8", "temurin-21.0.1". null when it is not recognised.
 */
public final class JavaVersionFile {
    private JavaVersionFile() {}

    private static final Pattern LINE = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9]*-)*(\\d{1,3}(?:\\.\\d{1,3})?)(?!\\d)");

    public static DockerfileJdk.Runtime parse(byte[] bytes) {
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            Matcher m = LINE.matcher(t);
            if (!m.find()) return null;
            String major = DockerfileJdk.versionMajor(m.group(1));
            return major == null ? null : new DockerfileJdk.Runtime(major, i + 1);
        }
        return null;
    }
}

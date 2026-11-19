package com.proofix.runner.config.spring;

import java.util.regex.Pattern;

/** [SEC] Config key / env var names whose values are treated as secrets. Name-based only. */
public final class SecretKeys {
    private SecretKeys() {}

    public static final Pattern SECRET = Pattern.compile(
            "password|passwd|secret|token|api[-_]?key|private[-_]?key|credential", Pattern.CASE_INSENSITIVE);

    public static boolean isSecret(String name) {
        return name != null && SECRET.matcher(name).find();
    }
}

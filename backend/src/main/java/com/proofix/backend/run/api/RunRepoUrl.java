package com.proofix.backend.run.api;

import java.util.Locale;
import java.util.regex.Pattern;

import com.proofix.backend.exception.BadRequestException;

/**
 * Strict repository URL rule for Runs: exactly
 * {@code https://github.com/{owner}/{repo}}. No other scheme, host, port,
 * userinfo, query, fragment, trailing slash, {@code .git} suffix, extra path
 * segment or percent-encoding. Stored lower-case (GitHub names are
 * case-insensitive).
 */
public final class RunRepoUrl {

    public static final int MAX_LENGTH = 200;

    private static final String PREFIX = "https://github.com/";

    /** GitHub login: 1-39 alphanumerics or single inner hyphens. */
    private static final Pattern OWNER =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");

    /** GitHub repository name: 1-100 of letters, digits, '.', '_', '-'. */
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private RunRepoUrl() {
    }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new BadRequestException("Repository URL is required");
        }
        String url = input.strip();
        if (url.length() > MAX_LENGTH) {
            throw new BadRequestException("Repository URL is too long");
        }
        if (!url.startsWith(PREFIX)) {
            throw invalid();
        }
        String[] segments = url.substring(PREFIX.length()).split("/", -1);
        if (segments.length != 2) {
            throw invalid();
        }
        String owner = segments[0];
        String repository = segments[1];
        if (!OWNER.matcher(owner).matches()
                || !REPOSITORY.matcher(repository).matches()
                || repository.equals(".")
                || repository.equals("..")
                || repository.toLowerCase(Locale.ROOT).endsWith(".git")) {
            throw invalid();
        }
        return PREFIX + owner.toLowerCase(Locale.ROOT) + "/" + repository.toLowerCase(Locale.ROOT);
    }

    private static BadRequestException invalid() {
        return new BadRequestException(
                "Repository URL must be exactly https://github.com/{owner}/{repo}");
    }
}

package com.proofix.backend.redaction;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Redacts credentials from repository file content before it is stored or
 * returned by the API.
 *
 * <p>Two rule sets are applied:
 * <ul>
 *   <li><b>High-confidence patterns</b>, applied to every file: private-key
 *       blocks, AWS access key IDs, GitHub tokens, Google API keys and the
 *       password part of {@code scheme://user:password@host} URLs.</li>
 *   <li><b>Sensitive key names</b> ({@code password}, {@code secret},
 *       {@code token}, {@code api_key}, ...), applied only to configuration
 *       files ({@code .properties}, {@code .yml/.yaml}, {@code .json},
 *       {@code .env}). Source code such as
 *       {@code String password = request.getPassword();} is never touched by
 *       this rule.</li>
 * </ul>
 *
 * <p>Pure placeholders ({@code ${VAR}}, {@code ${VAR:}},
 * {@code ${{ secrets.VAR }}}) are left as they are. A placeholder with a
 * literal default ({@code ${VAR:value}}) is treated as a secret.
 *
 * <p>Every pattern is bounded or possessive, and values are parsed without
 * regular expressions, so redaction runs in linear time on any input.
 */
@Component
public class SecretRedactor {

    static final String REDACTED = "[REDACTED]";
    static final String REDACTED_PRIVATE_KEY = "[REDACTED_PRIVATE_KEY]";
    static final String REDACTED_AWS_KEY_ID = "[REDACTED_AWS_ACCESS_KEY_ID]";
    static final String REDACTED_GITHUB_TOKEN = "[REDACTED_GITHUB_TOKEN]";
    static final String REDACTED_GOOGLE_API_KEY = "[REDACTED_GOOGLE_API_KEY]";

    private static final List<String> CONFIGURATION_EXTENSIONS = List.of(
            ".properties", ".yml", ".yaml", ".json", ".env");

    /** Fragments of key names that mark a value as sensitive (normalized). */
    private static final List<String> SENSITIVE_KEY_FRAGMENTS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey",
            "accesskey", "privatekey", "credential");

    private static final Pattern PRIVATE_KEY_BEGIN = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9]++ ){0,4}PRIVATE KEY-----");
    private static final Pattern PRIVATE_KEY_END = Pattern.compile(
            "-----END (?:[A-Z0-9]++ ){0,4}PRIVATE KEY-----");

    private static final Pattern AWS_ACCESS_KEY_ID = Pattern.compile(
            "(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])");
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:gh[pousr]_[A-Za-z0-9]{36,255}+"
                    + "|github_pat_[A-Za-z0-9_]{22,255}+)(?![A-Za-z0-9_])");
    private static final Pattern GOOGLE_API_KEY = Pattern.compile(
            "(?<![A-Za-z0-9_\\-])AIza[A-Za-z0-9_\\-]{35}(?![A-Za-z0-9_\\-])");

    /**
     * Group 1: {@code ://user:}, group 2: password, then {@code @}. Anchored
     * on {@code ://} (preceded by a scheme character) instead of scanning a
     * scheme at every position, which keeps the search cheap on long input.
     */
    private static final Pattern CONNECTION_STRING_PASSWORD = Pattern.compile(
            "(?<=[A-Za-z0-9+.\\-])(://[^\\s:/@'\"]{1,256}+:)"
                    + "([^\\s/@'\"]{1,256}+)@");

    /**
     * A key followed by {@code =} or {@code :}. Group 2 is the key name. The
     * value is parsed by hand in {@link #redactSensitiveKeys(String)}.
     */
    private static final Pattern KEY_ASSIGNMENT = Pattern.compile(
            "(?<![A-Za-z0-9_.\\-])([\"']?)([A-Za-z_][A-Za-z0-9_.\\-]{0,127}+)"
                    + "([\"']?)[ \\t]{0,32}+[=:][ \\t]{0,32}+");

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$\\{[A-Za-z_][A-Za-z0-9_.\\-]*+:?\\}"
                    + "|\\$\\{\\{[ \\t]*+[A-Za-z0-9_.\\-]++[ \\t]*+\\}\\}");

    /**
     * Redacts secrets from {@code content}.
     *
     * @param repositoryPath repository-relative path; decides whether the
     *        configuration key-name rule applies ({@code null} = source)
     * @param content file text, never {@code null}
     */
    public RedactionResult redact(String repositoryPath, String content) {
        if (content == null) {
            throw new IllegalArgumentException("Content to redact is required");
        }

        String redacted = redactPrivateKeys(content);
        redacted = replaceAll(AWS_ACCESS_KEY_ID, redacted,
                match -> REDACTED_AWS_KEY_ID);
        redacted = replaceAll(GITHUB_TOKEN, redacted,
                match -> REDACTED_GITHUB_TOKEN);
        redacted = replaceAll(GOOGLE_API_KEY, redacted,
                match -> REDACTED_GOOGLE_API_KEY);
        redacted = replaceAll(CONNECTION_STRING_PASSWORD, redacted,
                match -> isLeftAlone(match.group(2))
                        ? match.group()
                        : match.group(1) + REDACTED + "@");

        if (isConfigurationFile(repositoryPath)) {
            redacted = redactSensitiveKeys(redacted);
        }

        return new RedactionResult(redacted, !redacted.equals(content));
    }

    /**
     * Returns whether the key-name rule applies to this path: files ending in
     * {@code .properties}, {@code .yml}, {@code .yaml}, {@code .json} or
     * {@code .env}, and dotenv files such as {@code .env.local}.
     */
    public static boolean isConfigurationFile(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.isBlank()) {
            return false;
        }
        String normalized = repositoryPath.replace('\\', '/');
        String fileName = normalized
                .substring(normalized.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            return true;
        }
        return CONFIGURATION_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private static String redactPrivateKeys(String content) {
        Matcher begin = PRIVATE_KEY_BEGIN.matcher(content);
        if (!begin.find()) {
            return content;
        }

        StringBuilder result = new StringBuilder(content.length());
        Matcher end = PRIVATE_KEY_END.matcher(content);
        int copiedUpTo = 0;
        int searchFrom = 0;
        while (searchFrom < content.length() && begin.find(searchFrom)) {
            result.append(content, copiedUpTo, begin.start())
                    .append(REDACTED_PRIVATE_KEY);
            // An unterminated block is redacted to the end of the content.
            copiedUpTo = end.find(begin.end()) ? end.end() : content.length();
            searchFrom = copiedUpTo;
        }
        result.append(content, copiedUpTo, content.length());
        return result.toString();
    }

    private static String redactSensitiveKeys(String content) {
        Matcher assignment = KEY_ASSIGNMENT.matcher(content);
        StringBuilder result = null;
        int copiedUpTo = 0;
        int searchFrom = 0;
        // Positions only move forward, so the end of the current line can be
        // reused for every key on it (keeps long single-line input linear).
        int knownLineEnd = -1;

        while (searchFrom < content.length() && assignment.find(searchFrom)) {
            int valueStart = assignment.end();
            searchFrom = valueStart;
            if (!isSensitiveKey(assignment.group(2))) {
                // Keep scanning inside this value, e.g. url=...&password=x
                continue;
            }

            if (valueStart > knownLineEnd) {
                knownLineEnd = lineEnd(content, valueStart);
            }
            ValueSpan value = parseValue(content, valueStart, knownLineEnd);
            searchFrom = Math.max(value.end(), valueStart);
            if (value.redactStart() >= value.redactEnd()
                    || isLeftAlone(value.candidate())) {
                continue;
            }

            if (result == null) {
                result = new StringBuilder(content.length());
            }
            result.append(content, copiedUpTo, value.redactStart())
                    .append(REDACTED);
            copiedUpTo = value.redactEnd();
        }

        if (result == null) {
            return content;
        }
        result.append(content, copiedUpTo, content.length());
        return result.toString();
    }

    /**
     * Parses the value that starts at {@code start}, up to the end of the
     * line. Quoted values are redacted inside the quotes (an unterminated
     * quote runs to the end of the line). Unquoted values run to the end of
     * the line, because .properties and .env values may contain spaces.
     */
    private static ValueSpan parseValue(String content, int start, int lineEnd) {
        if (start >= lineEnd) {
            return new ValueSpan(start, start, start, "");
        }

        char first = content.charAt(start);
        if (first == '"' || first == '\'') {
            int index = start + 1;
            while (index < lineEnd) {
                char current = content.charAt(index);
                if (first == '"' && current == '\\') {
                    index += 2;
                    continue;
                }
                if (current == first) {
                    return new ValueSpan(start + 1, index, index + 1,
                            content.substring(start + 1, index));
                }
                index++;
            }
            return new ValueSpan(start + 1, lineEnd, lineEnd,
                    content.substring(start + 1, lineEnd));
        }

        int end = lineEnd;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
            end--;
        }
        String value = content.substring(start, end);
        return new ValueSpan(start, end, lineEnd, withoutTrailingComment(value));
    }

    private static int lineEnd(String content, int from) {
        for (int index = from; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
        }
        return content.length();
    }

    /** Strips a trailing {@code # comment} preceded by whitespace. */
    private static String withoutTrailingComment(String value) {
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) == '#'
                    && Character.isWhitespace(value.charAt(index - 1))) {
                return value.substring(0, index).strip();
            }
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    /** Values that are empty, pure placeholders or already redacted. */
    private static boolean isLeftAlone(String value) {
        String trimmed = value.strip();
        return trimmed.isEmpty()
                || trimmed.startsWith("[REDACTED")
                || PLACEHOLDER.matcher(trimmed).matches();
    }

    private static String replaceAll(
            Pattern pattern,
            String content,
            Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        StringBuilder result = new StringBuilder(content.length());
        do {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(replacement.apply(matcher)));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Redaction outcome. {@code content} is never {@code null}, so a stored
     * file always has content, content hash and {@code redacted} flag set
     * together (database constraint {@code chk_snapshot_file_content}).
     */
    public record RedactionResult(String content, boolean redacted) {}

    private record ValueSpan(
            int redactStart, int redactEnd, int end, String candidate) {}
}

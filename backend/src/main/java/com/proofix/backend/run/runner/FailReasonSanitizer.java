package com.proofix.backend.run.runner;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.redaction.SecretRedactor;

/**
 * A runner's failure reason is untrusted text shown to the user and stored
 * in failure_reason and the run_events note. In this order: at most 2000 raw
 * characters; control characters become spaces and whitespace collapses;
 * SecretRedactor runs (key-name rule included); then the result is cut to
 * 500 characters (after redaction, so a secret is never half-cut first).
 */
@Component
public class FailReasonSanitizer {

    static final int MAX_RAW = 2000;
    static final int MAX_STORED = 500;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String REDACTION_PATH = "runner-failure.env";

    private final SecretRedactor redactor;

    public FailReasonSanitizer(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    public String sanitize(String raw) {
        if (raw == null) {
            throw new BadRequestException("reason is required");
        }
        if (raw.length() > MAX_RAW) {
            throw new BadRequestException("reason must be at most " + MAX_RAW + " characters");
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        raw.chars().forEach(c -> cleaned.append(Character.isISOControl(c) ? ' ' : (char) c));
        String text = WHITESPACE.matcher(cleaned).replaceAll(" ").strip();
        if (text.isEmpty()) {
            throw new BadRequestException("reason is required");
        }
        text = redactor.redact(REDACTION_PATH, text).content();
        if (text.length() > MAX_STORED) {
            int end = MAX_STORED;
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            text = text.substring(0, end);
        }
        return text;
    }
}

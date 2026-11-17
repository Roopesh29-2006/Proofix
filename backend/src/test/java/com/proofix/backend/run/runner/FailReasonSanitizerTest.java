package com.proofix.backend.run.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.redaction.SecretRedactor;

class FailReasonSanitizerTest {

    private final FailReasonSanitizer sanitizer = new FailReasonSanitizer(new SecretRedactor());

    @Test
    void controlCharactersBecomeSpacesAndWhitespaceCollapses() {
        assertEquals("a b c d", sanitizer.sanitize("  a\n\nb\t\u0000c\u001b[31m".replace("[31m", "")
                + " \u007f d  "));
    }

    @Test
    void secretsAreRedacted() {
        String aws = "AKIA" + "Q".repeat(16);
        String token = "ghp_" + "D".repeat(36);
        String result = sanitizer.sanitize("key " + aws + " and " + token
                + " and postgres://app:hunter2@db/x and DB_PASSWORD=supersecret1");

        assertFalse(result.contains(aws));
        assertFalse(result.contains(token));
        assertFalse(result.contains("hunter2"));
        assertFalse(result.contains("supersecret1"));
        assertTrue(result.contains("[REDACTED"), result);
    }

    /** Redaction runs before the cut, so a secret straddling position 500 is still redacted. */
    @Test
    void cappedAt500AfterRedaction() {
        String token = "ghp_" + "E".repeat(36);
        String result = sanitizer.sanitize("x".repeat(480) + " " + token);

        assertEquals(500, result.length());
        assertFalse(result.contains("ghp_EEEE"), result);
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String result = sanitizer.sanitize("x".repeat(499) + "😀" + "tail");

        assertEquals(499, result.length());
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)));
    }

    @Test
    void blankOrOverlongInputIsRejected() {
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(null));
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(""));
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize(" \n\t\u0000 "));
        assertThrows(BadRequestException.class, () -> sanitizer.sanitize("r".repeat(2001)));
        assertEquals(500, sanitizer.sanitize("r".repeat(2000)).length());
    }
}

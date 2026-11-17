package com.proofix.backend.runnerkey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Runner key format and hashing. A key is {@value #PREFIX} followed by the
 * URL-safe base64 (no padding) of 32 random bytes; only the lowercase hex
 * SHA-256 of the whole key is stored. Never log a key or its hash.
 */
public final class RunnerKeyCodec {

    public static final String PREFIX = "dbr_";

    private static final int KEY_BYTES = 32;
    private static final Pattern SHAPE = Pattern.compile("dbr_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RunnerKeyCodec() {
    }

    public static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** True if {@code value} has the shape of a runner key (says nothing about validity). */
    public static boolean looksLikeKey(String value) {
        return value != null && SHAPE.matcher(value).matches();
    }

    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

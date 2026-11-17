package com.proofix.backend.runnerkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RunnerKeyGeneratorTest {

    @Test
    void keyIsPrefixedUrlSafeBase64Of32Bytes() {
        String key = RunnerKeyCodec.generate();

        assertTrue(key.startsWith("dbr_"), key.substring(0, 4));
        String body = key.substring(4);
        assertEquals(43, body.length());
        assertTrue(body.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(body).length);
        assertTrue(RunnerKeyCodec.looksLikeKey(key));
    }

    @Test
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(RunnerKeyCodec.generate());
        }
        assertEquals(1000, keys.size());
    }

    @Test
    void hashIsLowercaseHexSha256OfTheWholeKey() throws Exception {
        String key = RunnerKeyCodec.generate();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));

        String hash = RunnerKeyCodec.hash(key);

        assertEquals(expected, hash);
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertFalse(hash.contains(key.substring(4)));
        assertNotEquals(RunnerKeyCodec.hash(key), RunnerKeyCodec.hash(RunnerKeyCodec.generate()));
    }

    @Test
    void looksLikeKeyRejectsOtherShapes() {
        String key = RunnerKeyCodec.generate();
        assertFalse(RunnerKeyCodec.looksLikeKey(null));
        assertFalse(RunnerKeyCodec.looksLikeKey(""));
        assertFalse(RunnerKeyCodec.looksLikeKey(key.substring(4)));
        assertFalse(RunnerKeyCodec.looksLikeKey("dbx_" + key.substring(4)));
        assertFalse(RunnerKeyCodec.looksLikeKey(key + "A"));
        assertFalse(RunnerKeyCodec.looksLikeKey(key.substring(0, key.length() - 1)));
        assertFalse(RunnerKeyCodec.looksLikeKey(key.substring(0, 10) + "+" + key.substring(11)));
        assertFalse(RunnerKeyCodec.looksLikeKey("eyJhbGciOiJIUzI1NiJ9.e30.abc"));
    }

    @Test
    void createdKeyToStringNeverContainsThePlaintext() {
        String key = RunnerKeyCodec.generate();
        CreatedRunnerKeyResponse response = new CreatedRunnerKeyResponse(
                UUID.randomUUID(), "laptop", key, OffsetDateTime.now());

        assertFalse(response.toString().contains(key));
        assertFalse(response.toString().contains(key.substring(4)));
    }
}

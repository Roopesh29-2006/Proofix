package com.proofix.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JwtServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    // Keys are generated per run; no key material is stored in the source.
    private static String randomBase64Key(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    // ---------------------------------------------------------------
    // Startup validation of the signing secret
    // ---------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingSecretWithClearMessage(String secret) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(secret, 3_600_000));

        assertTrue(failure.getMessage().contains("JWT_SECRET"),
                failure.getMessage());
    }

    @Test
    void rejectsSecretThatIsNotValidBase64() {
        String secret = "not*valid*base64!!";

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(secret, 3_600_000));

        assertTrue(failure.getMessage().contains("base64"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(secret),
                "the secret must never appear in the message");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 31})
    void rejectsSecretShorterThan32Bytes(int bytes) {
        String secret = randomBase64Key(bytes);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new JwtService(secret, 3_600_000));

        assertTrue(failure.getMessage().contains("at least 32 bytes"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains(bytes + " bytes"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(secret),
                "the secret must never appear in the message");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveExpiration(long expirationMs) {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(randomBase64Key(32), expirationMs));
    }

    @Test
    void acceptsSecretOfExactly32Bytes() {
        JwtService service = new JwtService(randomBase64Key(32), 3_600_000);

        String token = service.generateToken(USER_ID, "user@example.com");

        assertTrue(service.isTokenValid(token));
        assertEquals(USER_ID.toString(), service.extractUserId(token));
    }

    @Test
    void acceptsSecretWithSurroundingWhitespace() {
        JwtService service = new JwtService(
                "  " + randomBase64Key(48) + "\n", 3_600_000);

        assertTrue(service.isTokenValid(
                service.generateToken(USER_ID, "user@example.com")));
    }

    @Test
    void buildsSigningKeyOnce() {
        JwtService service = new JwtService(randomBase64Key(32), 3_600_000);

        assertSame(service.signingKey(), service.signingKey());
    }

    // ---------------------------------------------------------------
    // Token verification
    // ---------------------------------------------------------------

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService issuer = new JwtService(randomBase64Key(32), 3_600_000);
        JwtService verifier = new JwtService(randomBase64Key(32), 3_600_000);

        String token = issuer.generateToken(USER_ID, "user@example.com");

        assertFalse(verifier.isTokenValid(token));
    }

    @Test
    void rejectsTamperedToken() {
        JwtService service = new JwtService(randomBase64Key(32), 3_600_000);
        String token = service.generateToken(USER_ID, "user@example.com");
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\"}")
                        .getBytes());

        assertFalse(service.isTokenValid(
                parts[0] + "." + forgedPayload + "." + parts[2]));
    }

    @Test
    void rejectsExpiredToken() {
        String secret = randomBase64Key(32);
        JwtService service = new JwtService(secret, 3_600_000);
        String expired = Jwts.builder()
                .subject(USER_ID.toString())
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)))
                .compact();

        assertFalse(service.isTokenValid(expired));
    }

    @Test
    void rejectsGarbageToken() {
        JwtService service = new JwtService(randomBase64Key(32), 3_600_000);

        assertFalse(service.isTokenValid("not-a-jwt"));
        assertFalse(service.isTokenValid(""));
    }
}

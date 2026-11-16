package com.proofix.backend.security;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>The signing secret is validated when the bean is created, so the
 * application refuses to start if {@code JWT_SECRET} is missing, is not valid
 * base64, or decodes to fewer than 32 bytes (256 bits). The main
 * configuration has no fallback key; only the test profile supplies one.
 * The signing key is built once and reused for every request.
 */
@Service
public class JwtService {

    static final int MINIMUM_KEY_BYTES = 32;

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.expiration-ms:3600000}") long expirationMs) {
        this.signingKey = buildSigningKey(secret);
        if (expirationMs <= 0) {
            throw new IllegalStateException(
                    "app.jwt.expiration-ms must be greater than zero.");
        }
        this.expirationMs = expirationMs;
    }

    private static SecretKey buildSigningKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret is not configured. Set the JWT_SECRET "
                            + "environment variable to a base64-encoded key of at "
                            + "least " + MINIMUM_KEY_BYTES + " bytes (256 bits), "
                            + "e.g. generated with: openssl rand -base64 48");
        }

        final byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret.strip());
        } catch (IllegalArgumentException exception) {
            // Never include the secret (or the decoder message) in the error.
            throw new IllegalStateException(
                    "JWT_SECRET is not valid base64. Provide a base64-encoded "
                            + "key of at least " + MINIMUM_KEY_BYTES + " bytes.");
        }

        if (keyBytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET decodes to " + keyBytes.length + " bytes; at least "
                            + MINIMUM_KEY_BYTES + " bytes (256 bits) are required.");
        }

        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** The signing key, built once at startup. */
    SecretKey signingKey() {
        return signingKey;
    }

    public String generateToken(UUID userId, String email) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUserId(String token) {
        return extractAllClaims(token).getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }
}

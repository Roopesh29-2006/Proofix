package com.proofix.backend.security;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.Date;
import java.util.UUID;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the real security filter chain (test profile, proofix_test database).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void protectedEndpointWithoutTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error", is("Authentication required")));
    }

    @Test
    void malformedTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Authentication required")));
    }

    @Test
    void tokenSignedWithAnotherKeyReturns401() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        String forged = Jwts.builder()
                .subject(USER_ID.toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(otherKey))
                .compact();

        mockMvc.perform(get("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        String expired = Jwts.builder()
                .subject(USER_ID.toString())
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(jwtService.signingKey())
                .compact();

        mockMvc.perform(get("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReachesProtectedEndpoint() throws Exception {
        String token = jwtService.generateToken(USER_ID, "user@example.com");

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER_ID.toString())));
    }

    @Test
    void publicLoginEndpointDoesNotRequireToken() throws Exception {
        // Invalid body: validation (400) proves the request passed security.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}

package com.proofix.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

class JsonSecurityErrorHandlerTest {

    private final JsonSecurityErrorHandler handler = new JsonSecurityErrorHandler();

    @Test
    void unauthenticatedRequestGets401Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(
                new MockHttpServletRequest(),
                response,
                new InsufficientAuthenticationException("internal detail"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"error\":\"Authentication required\"}",
                response.getContentAsString());
    }

    @Test
    void deniedRequestGets403Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest(),
                response,
                new AccessDeniedException("internal detail"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"error\":\"Access denied\"}",
                response.getContentAsString());
    }
}

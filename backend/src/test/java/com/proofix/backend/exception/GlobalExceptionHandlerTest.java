package com.proofix.backend.exception;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void illegalArgumentIsBadRequestNotConflict() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid GitHub repository URL")));
    }

    @Test
    void conflictExceptionIs409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error",
                        is("An account with this email already exists")));
    }

    @Test
    void resourceNotFoundIs404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Project not found")));
    }

    @Test
    void dataIntegrityViolationIs409WithoutDatabaseDetails() throws Exception {
        mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error",
                        is("The request conflicts with existing data")))
                .andExpect(content().string(not(containsString("users_email_key"))));
    }

    @Test
    void unexpectedExceptionIs500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error", is("Internal server error")))
                .andExpect(content().string(not(containsString("secret detail"))));
    }

    @Test
    void malformedJsonBodyIs400() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Malformed request body")));
    }

    @Test
    void invalidUuidPathVariableIs400() throws Exception {
        mockMvc.perform(get("/test/items/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Invalid value for parameter 'id'")));
    }

    @Test
    void missingRequestParameterIs400() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodKeepsItsStatusInsteadOf500() throws Exception {
        mockMvc.perform(post("/test/not-found"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void badCredentialsIs401() throws Exception {
        mockMvc.perform(get("/test/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Invalid email or password")));
    }

    @Test
    void missingAuthenticationIs401() throws Exception {
        mockMvc.perform(get("/test/no-authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Authentication required")));
    }

    @Test
    void repositoryAccessDeniedIs403() throws Exception {
        mockMvc.perform(get("/test/private-repository"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "GitHub {0} -> {1}")
    @CsvSource({
            "401, 502",
            "403, 502",
            "422, 502",
            "500, 502",
            "502, 502",
            "404, 404",
            "429, 429",
            "503, 503"})
    void gitHubFailuresAreMappedSoUpstreamAuthErrorsNeverLookLikeOurs(
            int gitHubStatus, int expectedStatus) throws Exception {
        mockMvc.perform(get("/test/github/{status}", gitHubStatus))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error", is("GitHub says " + gitHubStatus)));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/illegal-argument")
        String illegalArgument() {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }

        @GetMapping("/test/conflict")
        String conflict() {
            throw new ConflictException("An account with this email already exists");
        }

        @GetMapping("/test/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Project not found");
        }

        @GetMapping("/test/data-integrity")
        String dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"users_email_key\"");
        }

        @GetMapping("/test/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret detail");
        }

        @PostMapping("/test/body")
        String body(@RequestBody Map<String, String> body) {
            return "ok";
        }

        @GetMapping("/test/items/{id}")
        String item(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/test/param")
        String param(@RequestParam String required) {
            return required;
        }

        @GetMapping("/test/bad-credentials")
        String badCredentials() {
            throw new BadCredentialsException("internal");
        }

        @GetMapping("/test/no-authentication")
        String noAuthentication() {
            throw new AuthenticationCredentialsNotFoundException("internal");
        }

        @GetMapping("/test/private-repository")
        String privateRepository() {
            throw new RepositoryAccessDeniedException(
                    "Private repositories require a project repository connection.");
        }

        @GetMapping("/test/github/{status}")
        String gitHub(@PathVariable int status) {
            throw new GitHubApiException("GitHub says " + status, status);
        }
    }
}

package com.proofix.backend.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.proofix.backend.exception.GitHubApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class GitHubApiClientTest {

    @Test
    void constructsWithProductionConfigurationConstructor() {
        GitHubApiClient client = new GitHubApiClient(
                "https://api.github.com",
                15,
                "");

        assertNotNull(client);
    }

    @Test
    void includesAuthorizationHeaderWhenTokenIsConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(
                "https://api.github.com",
                15,
                "test-token",
                builder);

        server.expect(once(), requestTo(
                "https://api.github.com/repos/acme/demo"))
                .andExpect(header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer test-token"))
                .andRespond(withSuccess(
                        "{\"default_branch\":\"main\"}",
                        MediaType.APPLICATION_JSON));

        JsonNode response = client.getRepositoryMetadata("acme", "demo");

        assertEquals("main", response.path("default_branch").asText());
        server.verify();
    }

    @Test
    void omitsAuthorizationHeaderWhenTokenIsMissing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(
                "https://api.github.com",
                15,
                "",
                builder);

        server.expect(once(), requestTo(
                "https://api.github.com/repos/acme/demo"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(
                        "{\"default_branch\":\"main\"}",
                        MediaType.APPLICATION_JSON));

        JsonNode response = client.getRepositoryMetadata("acme", "demo");

        assertEquals("main", response.path("default_branch").asText());
        server.verify();
    }

    @Test
    void retrievesLatestCommitForBranch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(
                "https://api.github.com",
                15,
                "",
                builder);

        server.expect(once(), requestTo(
                "https://api.github.com/repos/acme/demo/commits/main"))
                .andRespond(withSuccess(
                        """
                        {
                          "sha": "commit-sha",
                          "commit": {
                            "message": "Initial commit",
                            "author": {"date": "2026-01-01T00:00:00Z"}
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        JsonNode response = client.getRepositoryLatestCommit(
                "acme", "demo", "main");

        assertEquals("commit-sha", response.path("sha").asText());
        assertEquals(
                "2026-01-01T00:00:00Z",
                response.path("commit").path("author").path("date").asText());
        server.verify();
    }

    @Test
    void mapsMissingLatestCommitToNotFound() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(
                "https://api.github.com",
                15,
                "",
                builder);

        server.expect(once(), requestTo(
                "https://api.github.com/repos/acme/demo/commits/main"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        GitHubApiException failure = org.junit.jupiter.api.Assertions
                .assertThrows(
                        GitHubApiException.class,
                        () -> client.getRepositoryLatestCommit(
                                "acme", "demo", "main"));

        assertEquals(404, failure.getStatusCode());
        server.verify();
    }

    @Test
    void mapsLatestCommitApiFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(
                "https://api.github.com",
                15,
                "",
                builder);

        server.expect(once(), requestTo(
                "https://api.github.com/repos/acme/demo/commits/main"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        GitHubApiException failure = org.junit.jupiter.api.Assertions
                .assertThrows(
                        GitHubApiException.class,
                        () -> client.getRepositoryLatestCommit(
                                "acme", "demo", "main"));

        assertEquals(502, failure.getStatusCode());
        server.verify();
    }
}

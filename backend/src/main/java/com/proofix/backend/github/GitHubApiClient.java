
package com.proofix.backend.github;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import com.proofix.backend.exception.GitHubApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
public class GitHubApiClient {

    private final RestClient restClient;

    @Autowired
    public GitHubApiClient(
            @Value("${github.api.base-url:https://api.github.com}")
            String baseUrl,
            @Value("${github.api.timeout-seconds:15}")
            int timeoutSeconds,
            @Value("${github.api.token:}")
            String token) {
        this(baseUrl, timeoutSeconds, token, RestClient.builder(), true);
    }

    GitHubApiClient(
            String baseUrl,
            int timeoutSeconds,
            String token,
            RestClient.Builder restClientBuilder) {
        this(baseUrl, timeoutSeconds, token, restClientBuilder, false);
    }

    private GitHubApiClient(
            String baseUrl,
            int timeoutSeconds,
            String token,
            RestClient.Builder restClientBuilder,
            boolean configureRequestFactory) {

        validateBaseUrl(baseUrl);

        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "GitHub API timeout must be greater than zero");
        }

        RestClient.Builder builder = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        "application/vnd.github+json")
                .defaultHeader(
                        "X-GitHub-Api-Version",
                        "2022-11-28")
                .defaultHeader(
                        HttpHeaders.USER_AGENT,
                        "DevBuddy-Repository-Analyzer");

        if (token != null && !token.isBlank()) {
            builder.defaultHeader(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + token);
        }

        if (configureRequestFactory) {
            SimpleClientHttpRequestFactory requestFactory =
                    new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(
                    Duration.ofSeconds(timeoutSeconds));
            requestFactory.setReadTimeout(
                    Duration.ofSeconds(timeoutSeconds));
            builder.requestFactory(requestFactory);
        }

        this.restClient = builder.build();
    }

    public JsonNode getRepositoryMetadata(
            String owner,
            String repository) {

        try {
            return restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repository)
                    .retrieve()
                    .body(JsonNode.class);

        } catch (HttpClientErrorException.NotFound exception) {
            throw new GitHubApiException(
                    "Repository not found or inaccessible",
                    404,
                    exception);

        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new GitHubApiException(
                    "GitHub API rate limit exceeded",
                    429,
                    exception);

        } catch (HttpClientErrorException exception) {
            int statusCode = exception.getStatusCode().value();

            if (statusCode == 401) {
                throw new GitHubApiException(
                        "GitHub authentication is required for this resource.",
                        401,
                        exception);
            }

            if (statusCode == 403) {
                throw new GitHubApiException(
                        "GitHub API rate limit exceeded or access forbidden",
                        403,
                        exception);
            }

            throw new GitHubApiException(
                    "GitHub API request failed",
                    statusCode,
                    exception);

        } catch (ResourceAccessException exception) {
            throw new GitHubApiException(
                    "Unable to connect to GitHub. Please try again later.",
                    503,
                    exception);

        } catch (RestClientException exception) {
            throw new GitHubApiException(
                    "GitHub API request failed.",
                    502,
                    exception);
        }
    }

    public JsonNode getRepositoryTree(
            String owner,
            String repository,
            String branch) {

        try {
            return restClient.get()
                    .uri(
                            "/repos/{owner}/{repo}/git/trees/{branch}?recursive=1",
                            owner,
                            repository,
                            branch)
                    .retrieve()
                    .body(JsonNode.class);

        } catch (HttpClientErrorException.NotFound exception) {
            throw new GitHubApiException(
                    "Repository or branch not found.",
                    404,
                    exception);

        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new GitHubApiException(
                    "GitHub API rate limit exceeded.",
                    429,
                    exception);

        } catch (HttpClientErrorException exception) {
            int statusCode = exception.getStatusCode().value();

            if (statusCode == 401) {
                throw new GitHubApiException(
                        "GitHub authentication is required for this resource.",
                        401,
                        exception);
            }

            throw new GitHubApiException(
                    statusCode == 403
                            ? "GitHub API access forbidden."
                            : "GitHub API request failed.",
                    statusCode,
                    exception);

        } catch (ResourceAccessException exception) {
            throw new GitHubApiException(
                    "Unable to connect to GitHub while retrieving repository files.",
                    503,
                    exception);

        } catch (RestClientException exception) {
            throw new GitHubApiException(
                    "Failed to retrieve repository file tree.",
                    502,
                    exception);
        }
    }

    public JsonNode getRepositoryLatestCommit(
            String owner,
            String repository,
            String branch) {

        try {
            return restClient.get()
                    .uri(
                            "/repos/{owner}/{repo}/commits/{branch}",
                            owner,
                            repository,
                            branch)
                    .retrieve()
                    .body(JsonNode.class);

        } catch (HttpClientErrorException.NotFound exception) {
            throw new GitHubApiException(
                    "Repository or branch not found.",
                    404,
                    exception);

        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new GitHubApiException(
                    "GitHub API rate limit exceeded.",
                    429,
                    exception);

        } catch (HttpClientErrorException exception) {
            int statusCode = exception.getStatusCode().value();

            if (statusCode == 401) {
                throw new GitHubApiException(
                        "GitHub authentication is required for this resource.",
                        401,
                        exception);
            }

            throw new GitHubApiException(
                    statusCode == 403
                            ? "GitHub API access forbidden."
                            : "GitHub API request failed.",
                    statusCode,
                    exception);

        } catch (ResourceAccessException exception) {
            throw new GitHubApiException(
                    "Unable to connect to GitHub while retrieving the latest commit.",
                    503,
                    exception);

        } catch (RestClientException exception) {
            throw new GitHubApiException(
                    "Failed to retrieve the latest repository commit.",
                    502,
                    exception);
        }
    }

    public JsonNode getRepositoryFile(
            String owner,
            String repository,
            String path) {
        return getRepositoryFile(owner, repository, path, null);
    }

    public JsonNode getRepositoryFile(
            String owner,
            String repository,
            String path,
            String reference) {

        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/repos/{owner}/{repo}/contents/{path}");

                        if (reference == null || reference.isBlank()) {
                            return builder.build(owner, repository, path);
                        }

                        return builder
                                .queryParam("ref", reference)
                                .build(owner, repository, path);
                    })
                    .retrieve()
                    .body(JsonNode.class);

        } catch (HttpClientErrorException.NotFound exception) {
            throw new GitHubApiException(
                    "Repository file not found.",
                    404,
                    exception);

        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new GitHubApiException(
                    "GitHub API rate limit exceeded.",
                    429,
                    exception);

        } catch (HttpClientErrorException exception) {
            int statusCode = exception.getStatusCode().value();

            throw new GitHubApiException(
                    statusCode == 403
                            ? "GitHub API access forbidden."
                            : "GitHub API request failed.",
                    statusCode,
                    exception);

        } catch (ResourceAccessException exception) {
            throw new GitHubApiException(
                    "Unable to connect to GitHub while retrieving a repository file.",
                    503,
                    exception);

        } catch (RestClientException exception) {
            throw new GitHubApiException(
                    "Failed to retrieve repository file.",
                    502,
                    exception);
        }
    }

    private static void validateBaseUrl(String baseUrl) {
        final URI uri;

        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "GitHub API base URL must be https://api.github.com",
                    exception);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"api.github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {

            throw new IllegalArgumentException(
                    "GitHub API base URL must be https://api.github.com");
        }
    }
}

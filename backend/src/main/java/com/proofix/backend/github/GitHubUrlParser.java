
package com.proofix.backend.github;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class GitHubUrlParser {

    private static final Pattern OWNER_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$"
            );

    private static final Pattern REPOSITORY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

    public RepositoryCoordinates parse(String repositoryUrl) {

        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository URL cannot be empty");
        }

        final URI uri;

        try {
            uri = new URI(repositoryUrl.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "Invalid GitHub repository URL");
        }

        // Only HTTPS is supported
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "Only HTTPS GitHub repository URLs are supported");
        }

        // Reject invalid hosts, credentials and custom ports
        if (!"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null) {

            throw new IllegalArgumentException(
                    "Only valid github.com repository URLs are supported");
        }

        // Reject query parameters and fragments
        if (uri.getRawQuery() != null
                || uri.getRawFragment() != null) {

            throw new IllegalArgumentException(
                    "Query parameters and fragments are not supported");
        }

        String rawPath = uri.getRawPath();

        if (rawPath == null || rawPath.isBlank()
                || rawPath.contains("%")
                || rawPath.contains("\\")) {

            throw new IllegalArgumentException(
                    "Invalid GitHub repository path");
        }

        // Remove trailing slashes
        String path = rawPath.replaceAll("/+$", "");

        String[] segments = path.split("/", -1);

        if (segments.length != 3
                || !segments[0].isEmpty()) {

            throw new IllegalArgumentException(
                    "URL must follow https://github.com/owner/repository");
        }

        String owner = segments[1];
        String repository = segments[2];

        // Support optional .git suffix
        if (repository.endsWith(".git")) {
            repository = repository.substring(
                    0, repository.length() - 4);
        }

        if (!OWNER_PATTERN.matcher(owner).matches()) {
            throw new IllegalArgumentException(
                    "Invalid GitHub repository owner");
        }

        if (!REPOSITORY_PATTERN.matcher(repository).matches()
                || repository.equals(".")
                || repository.equals("..")) {

            throw new IllegalArgumentException(
                    "Invalid GitHub repository name");
        }

        return new RepositoryCoordinates(owner, repository);
    }

    public record RepositoryCoordinates(
            String owner,
            String repository
    ) {}
}

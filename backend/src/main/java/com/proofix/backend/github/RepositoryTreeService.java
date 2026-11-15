
package com.proofix.backend.github;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.proofix.backend.exception.GitHubApiException;
import com.proofix.backend.github.GitHubUrlParser.RepositoryCoordinates;
import tools.jackson.databind.JsonNode;

@Service
public class RepositoryTreeService {

    private static final int MAX_TREE_ENTRIES = 300;

    private final GitHubApiClient gitHubApiClient;

    public RepositoryTreeService(
            GitHubApiClient gitHubApiClient) {
        this.gitHubApiClient = gitHubApiClient;
    }

    public RepositoryTree getRepositoryTree(
            String owner,
            String repository,
            String defaultBranch) {

        if (owner == null || owner.isBlank()
                || repository == null || repository.isBlank()
                || defaultBranch == null || defaultBranch.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository owner, name, and branch are required");
        }

        JsonNode response = gitHubApiClient.getRepositoryTree(
                owner,
                repository,
                defaultBranch);

        if (response == null || !response.path("tree").isArray()) {
            throw new GitHubApiException(
                    "GitHub returned an invalid repository tree.",
                    502);
        }

        JsonNode tree = response.path("tree");

        List<RepositoryFile> entries = new ArrayList<>();

        int filesSkipped = 0;

        for (JsonNode node : tree) {

            if (entries.size() >= MAX_TREE_ENTRIES) {
                filesSkipped++;
                continue;
            }

            if (node == null || !node.isObject()) {
                filesSkipped++;
                continue;
            }

            String path = node.path("path").asText(null);
            String type = node.path("type").asText(null);

            if (path == null || path.isBlank()
                    || type == null) {
                filesSkipped++;
                continue;
            }

            if (!"blob".equals(type) && !"tree".equals(type)) {
                filesSkipped++;
                continue;
            }

            long size = node.path("size").asLong(0);
            if (size < 0) {
                filesSkipped++;
                continue;
            }

            entries.add(new RepositoryFile(
                    path,
                    type,
                    size,
                    node.path("sha").asText(null)));
        }

        boolean truncated = response.path("truncated").asBoolean(false);

        return new RepositoryTree(
                List.copyOf(entries),
                filesSkipped,
                truncated);
    }

    public RepositoryTree getRepositoryTree(
            RepositoryCoordinates coordinates,
            String defaultBranch) {

        if (coordinates == null) {
            throw new IllegalArgumentException(
                    "Repository coordinates are required");
        }

        return getRepositoryTree(
                coordinates.owner(),
                coordinates.repository(),
                defaultBranch);
    }

    public record RepositoryFile(
            String path,
            String type,
            long size,
            String sha) {}

    public record RepositoryTree(
            List<RepositoryFile> entries,
            int filesSkipped,
            boolean truncated) {}
}

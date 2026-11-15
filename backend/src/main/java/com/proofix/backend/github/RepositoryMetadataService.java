package com.proofix.backend.github;

import com.proofix.backend.github.GitHubUrlParser.RepositoryCoordinates;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class RepositoryMetadataService {

    private final GitHubApiClient gitHubApiClient;

    public RepositoryMetadataService(GitHubApiClient gitHubApiClient) {
        this.gitHubApiClient = gitHubApiClient;
    }

    public JsonNode getRepositoryMetadata(
            RepositoryCoordinates coordinates) {

        if (coordinates == null) {
            throw new IllegalArgumentException(
                    "Repository coordinates are required");
        }

        return gitHubApiClient.getRepositoryMetadata(
                coordinates.owner(),
                coordinates.repository());
    }

    public JsonNode getRepositoryLatestCommit(
            RepositoryCoordinates coordinates,
            String branch) {

        if (coordinates == null) {
            throw new IllegalArgumentException(
                    "Repository coordinates are required");
        }
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository branch is required");
        }

        return gitHubApiClient.getRepositoryLatestCommit(
                coordinates.owner(),
                coordinates.repository(),
                branch);
    }
}

package com.proofix.backend.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.proofix.backend.github.RepositoryTreeService.RepositoryFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RepositoryFileReaderTest {

    private final GitHubApiClient gitHubApiClient =
            mock(GitHubApiClient.class);
    private final RepositoryFileReader reader = new RepositoryFileReader(
            gitHubApiClient,
            new RepositoryFileClassifier(),
            100,
            1024,
            2048);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsAndRedactsRelevantTextFiles() throws Exception {
        when(gitHubApiClient.getRepositoryFile(
                "acme", "demo", "src/App.java", "main"))
                .thenReturn(fileResponse(
                        "password = \"not-a-real-password\""));

        RepositoryTree tree = new RepositoryTree(
                List.of(new RepositoryFile(
                        "src/App.java", "blob", 40, "sha")),
                0,
                false);

        RepositoryFileReader.FileReadResult result =
                reader.readRelevantFiles(
                        new GitHubUrlParser.RepositoryCoordinates(
                                "acme", "demo"),
                        tree,
                        "main");

        assertEquals(1, result.files().size());
        assertTrue(result.files().get(0).secretsRedacted());
        assertTrue(result.files().get(0).content().contains("[REDACTED]"));
    }

    @Test
    void skipsBinaryFilesAndUnsupportedPaths() throws Exception {
        when(gitHubApiClient.getRepositoryFile(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(fileResponse("\u0000binary"));

        RepositoryTree tree = new RepositoryTree(
                List.of(
                        new RepositoryFile(
                                "image.png", "blob", 10, "sha"),
                        new RepositoryFile(
                                "README.md", "blob", 10, "sha")),
                0,
                false);

        RepositoryFileReader.FileReadResult result =
                reader.readRelevantFiles(
                        new GitHubUrlParser.RepositoryCoordinates(
                                "acme", "demo"),
                        tree,
                        "main");

        assertTrue(result.files().isEmpty());
        assertEquals(2, result.filesSkipped());
    }

    private JsonNode fileResponse(String content) throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return objectMapper.readTree("""
                {
                  "type": "file",
                  "encoding": "base64",
                  "content": "%s"
                }
                """.formatted(encoded));
    }
}

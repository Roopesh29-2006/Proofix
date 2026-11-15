package com.proofix.backend.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.proofix.backend.github.RepositoryTreeService.RepositoryFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import com.proofix.backend.redaction.SecretRedactor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RepositoryFileReaderTest {

    private final GitHubApiClient gitHubApiClient =
            mock(GitHubApiClient.class);
    private final RepositoryFileReader reader = new RepositoryFileReader(
            gitHubApiClient,
            new RepositoryFileClassifier(),
            new SecretRedactor(),
            100,
            1024,
            2048);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsAndRedactsRelevantConfigurationFiles() throws Exception {
        when(gitHubApiClient.getRepositoryFile(
                "acme", "demo", "src/main/resources/application.properties", "main"))
                .thenReturn(fileResponse(
                        "password = \"not-a-real-password\""));

        RepositoryFileReader.FileReadResult result = readSingle(
                "src/main/resources/application.properties");

        assertEquals(1, result.files().size());
        assertTrue(result.files().get(0).secretsRedacted());
        assertEquals("password = \"[REDACTED]\"",
                result.files().get(0).content());
    }

    @Test
    void doesNotRedactKeyNamesInJavaSource() throws Exception {
        String source = "String password = request.getPassword();";
        when(gitHubApiClient.getRepositoryFile(
                "acme", "demo", "src/App.java", "main"))
                .thenReturn(fileResponse(source));

        RepositoryFileReader.FileReadResult result = readSingle("src/App.java");

        assertEquals(source, result.files().get(0).content());
        assertFalse(result.files().get(0).secretsRedacted());
    }

    @Test
    void scannedFileWithoutSecretsStillHasContentAndFlag() throws Exception {
        // chk_snapshot_file_content: content, hash and secrets_redacted must
        // be all set for a scanned file, including when nothing was redacted.
        when(gitHubApiClient.getRepositoryFile(
                "acme", "demo", "application.yml", "main"))
                .thenReturn(fileResponse("server:\n  port: 8082\n"));

        RepositoryFileReader.ScannedFile file =
                readSingle("application.yml").files().get(0);

        assertNotNull(file.content());
        assertEquals("server:\n  port: 8082\n", file.content());
        assertFalse(file.secretsRedacted());
    }

    @Test
    void rejectsMissingSecretRedactor() {
        assertThrows(IllegalArgumentException.class, () ->
                new RepositoryFileReader(
                        gitHubApiClient,
                        new RepositoryFileClassifier(),
                        null,
                        100,
                        1024,
                        2048));
    }

    private RepositoryFileReader.FileReadResult readSingle(String path) {
        RepositoryTree tree = new RepositoryTree(
                List.of(new RepositoryFile(path, "blob", 40, "sha")),
                0,
                false);
        return reader.readRelevantFiles(
                new GitHubUrlParser.RepositoryCoordinates("acme", "demo"),
                tree,
                "main");
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

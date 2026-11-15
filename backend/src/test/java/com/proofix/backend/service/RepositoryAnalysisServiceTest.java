package com.proofix.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.proofix.backend.exception.GitHubApiException;
import com.proofix.backend.github.GitHubUrlParser;
import com.proofix.backend.github.GitHubUrlParser.RepositoryCoordinates;
import com.proofix.backend.github.RepositoryFileClassifier;
import com.proofix.backend.github.RepositoryFileReader;
import com.proofix.backend.github.RepositoryFileReader.FileReadResult;
import com.proofix.backend.github.RepositoryMetadataService;
import com.proofix.backend.github.RepositoryTreeService;
import com.proofix.backend.github.RepositoryTreeService.RepositoryFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class RepositoryAnalysisServiceTest {

    private static final String REPOSITORY_URL =
            "https://github.com/acme/demo";

    @Mock
    private GitHubUrlParser gitHubUrlParser;

    @Mock
    private RepositoryMetadataService repositoryMetadataService;

    @Mock
    private RepositoryTreeService repositoryTreeService;

    @Mock
    private RepositoryFileClassifier repositoryFileClassifier;

    @Mock
    private RepositoryFileReader repositoryFileReader;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RepositoryAnalysisService service;
    private RepositoryCoordinates coordinates;

    @BeforeEach
    void setUp() {
        service = new RepositoryAnalysisService(
                gitHubUrlParser,
                repositoryMetadataService,
                repositoryTreeService,
                repositoryFileClassifier,
                repositoryFileReader);
        coordinates = new RepositoryCoordinates("acme", "demo");
    }

    @Test
    void analyzesRepositoryAndPreservesMetadataTreeAndScannedFiles()
            throws Exception {
        RepositoryTree tree = new RepositoryTree(
                List.of(new RepositoryFile(
                        "src/App.java", "blob", 20, "sha")),
                0,
                false);
        RepositoryFileReader.ScannedFile scannedFile =
                new RepositoryFileReader.ScannedFile(
                        "src/App.java",
                        RepositoryFileClassifier.FileCategory.SOURCE,
                        "class App {}",
                        false);

        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(metadata());
        when(repositoryMetadataService.getRepositoryLatestCommit(
                coordinates, "main"))
                .thenReturn(commit());
        when(repositoryTreeService.getRepositoryTree(coordinates, "main"))
                .thenReturn(tree);
        when(repositoryFileClassifier.classify("src/App.java"))
                .thenReturn(RepositoryFileClassifier.FileCategory.SOURCE);
        when(repositoryFileReader.readRelevantFiles(
                coordinates, tree, "main"))
                .thenReturn(new FileReadResult(
                        List.of(scannedFile),
                        0,
                        12));

        RepositoryAnalysisService.AnalysisResult result =
                service.analyze(REPOSITORY_URL);

        assertEquals("demo", result.metadata().repositoryName());
        assertEquals("octocat", result.metadata().ownerLogin());
        assertEquals("main", result.metadata().defaultBranch());
        assertEquals("commit-sha", result.commit().sha());
        assertEquals("Initial commit", result.commit().message());
        assertEquals(
                "2026-01-01T00:00:00Z",
                result.commit().authorDate());
        assertEquals(1, result.classifiedFiles().size());
        assertEquals(
                RepositoryFileClassifier.FileCategory.SOURCE,
                result.classifiedFiles().get(0).category());
        assertSame(scannedFile, result.scannedFiles().get(0));
        assertEquals(12, result.bytesDownloaded());
    }

    @Test
    void rejectsInvalidRepositoryUrl() {
        IllegalArgumentException failure =
                new IllegalArgumentException("Invalid GitHub repository URL");
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenThrow(failure);

        assertSame(failure, assertThrows(
                IllegalArgumentException.class,
                () -> service.analyze(REPOSITORY_URL)));
        verifyNoInteractions(repositoryMetadataService);
    }

    @Test
    void rejectsMetadataWithoutDefaultBranch() throws Exception {
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(objectMapper.readTree("""
                        {"name":"demo","owner":{"login":"octocat"}}
                        """));

        GitHubApiException failure = assertThrows(
                GitHubApiException.class,
                () -> service.analyze(REPOSITORY_URL));

        assertEquals(502, failure.getStatusCode());
        verifyNoInteractions(repositoryTreeService);
    }

    @Test
    void propagatesRepositoryNotFound() {
        GitHubApiException failure =
                new GitHubApiException("Repository not found or inaccessible", 404);
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenThrow(failure);

        assertSame(failure, assertThrows(
                GitHubApiException.class,
                () -> service.analyze(REPOSITORY_URL)));
    }

    @Test
    void propagatesGitHubApiFailure() throws Exception {
        GitHubApiException failure =
                new GitHubApiException("GitHub API request failed", 503);
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(metadata());
        when(repositoryMetadataService.getRepositoryLatestCommit(
                coordinates, "main"))
                .thenReturn(commit());
        when(repositoryTreeService.getRepositoryTree(coordinates, "main"))
                .thenThrow(failure);

        assertSame(failure, assertThrows(
                GitHubApiException.class,
                () -> service.analyze(REPOSITORY_URL)));
    }

    @Test
    void supportsEmptyRepositoryTree() throws Exception {
        RepositoryTree tree = new RepositoryTree(List.of(), 0, false);
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(metadata());
        when(repositoryMetadataService.getRepositoryLatestCommit(
                coordinates, "main"))
                .thenReturn(commit());
        when(repositoryTreeService.getRepositoryTree(coordinates, "main"))
                .thenReturn(tree);
        when(repositoryFileReader.readRelevantFiles(
                coordinates, tree, "main"))
                .thenReturn(new FileReadResult(List.of(), 0, 0));

        RepositoryAnalysisService.AnalysisResult result =
                service.analyze(REPOSITORY_URL);

        assertEquals(List.of(), result.classifiedFiles());
        assertEquals(List.of(), result.scannedFiles());
        assertEquals(0, result.bytesDownloaded());
    }

    @Test
    void preservesSkippedFilesAndTreeTruncation() throws Exception {
        RepositoryTree tree = new RepositoryTree(
                List.of(new RepositoryFile(
                        "src/App.java", "blob", 20, "sha")),
                4,
                true);
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(metadata());
        when(repositoryMetadataService.getRepositoryLatestCommit(
                coordinates, "main"))
                .thenReturn(commit());
        when(repositoryTreeService.getRepositoryTree(coordinates, "main"))
                .thenReturn(tree);
        when(repositoryFileClassifier.classify("src/App.java"))
                .thenReturn(RepositoryFileClassifier.FileCategory.SOURCE);
        when(repositoryFileReader.readRelevantFiles(
                coordinates, tree, "main"))
                .thenReturn(new FileReadResult(List.of(), 3, 0));

        RepositoryAnalysisService.AnalysisResult result =
                service.analyze(REPOSITORY_URL);

        assertEquals(4, result.treeEntriesSkipped());
        assertEquals(3, result.filesSkipped());
        assertEquals(true, result.treeTruncated());
    }

    @Test
    void rejectsCommitWithoutSha() throws Exception {
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(metadata());
        when(repositoryMetadataService.getRepositoryLatestCommit(
                coordinates, "main"))
                .thenReturn(objectMapper.readTree("""
                        {
                          "sha": " ",
                          "commit": {
                            "message": "Initial commit",
                            "author": {"date": "2026-01-01T00:00:00Z"}
                          }
                        }
                        """));

        GitHubApiException failure = assertThrows(
                GitHubApiException.class,
                () -> service.analyze(REPOSITORY_URL));

        assertEquals(502, failure.getStatusCode());
        verifyNoInteractions(repositoryTreeService);
    }

    @Test
    void rejectsCommitWithMissingSha() throws Exception {
        when(gitHubUrlParser.parse(REPOSITORY_URL)).thenReturn(coordinates);
        when(repositoryMetadataService.getRepositoryMetadata(coordinates))
                .thenReturn(metadata());
        when(repositoryMetadataService.getRepositoryLatestCommit(
                coordinates, "main"))
                .thenReturn(objectMapper.readTree("""
                        {
                          "commit": {
                            "message": "Initial commit",
                            "author": {"date": "2026-01-01T00:00:00Z"}
                          }
                        }
                        """));

        GitHubApiException failure = assertThrows(
                GitHubApiException.class,
                () -> service.analyze(REPOSITORY_URL));

        assertEquals(502, failure.getStatusCode());
        verifyNoInteractions(repositoryTreeService);
    }

    private JsonNode metadata() throws Exception {
        return objectMapper.readTree("""
                {
                  "name": "demo",
                  "owner": {"login": "octocat"},
                  "description": "Demo repository",
                  "html_url": "https://github.com/acme/demo",
                  "default_branch": "main",
                  "language": "Java",
                  "visibility": "public",
                  "fork": false,
                  "stargazers_count": 7,
                  "forks_count": 2
                }
                """);
    }

    private JsonNode commit() {
        return objectMapper.createObjectNode()
                .put("sha", "commit-sha")
                .set("commit", objectMapper.createObjectNode()
                        .put("message", "Initial commit")
                        .set("author", objectMapper.createObjectNode()
                                .put("date", "2026-01-01T00:00:00Z")));
    }
}

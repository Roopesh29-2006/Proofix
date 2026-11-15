package com.proofix.backend.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.proofix.backend.exception.GitHubApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RepositoryTreeServiceTest {

    private final GitHubApiClient gitHubApiClient =
            mock(GitHubApiClient.class);
    private final RepositoryTreeService service =
            new RepositoryTreeService(gitHubApiClient);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsSupportedEntriesAndReportsSkippedEntries() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "truncated": true,
                  "tree": [
                    {"path": "src/App.java", "type": "blob", "size": 20, "sha": "a"},
                    {"path": "src", "type": "tree", "sha": "b"},
                    {"path": "", "type": "blob"},
                    {"path": "link", "type": "commit"}
                  ]
                }
                """);

        when(gitHubApiClient.getRepositoryTree(
                "acme", "demo", "main")).thenReturn(response);

        RepositoryTreeService.RepositoryTree tree =
                service.getRepositoryTree("acme", "demo", "main");

        assertEquals(2, tree.entries().size());
        assertEquals("src/App.java", tree.entries().get(0).path());
        assertEquals(2, tree.filesSkipped());
        assertEquals(true, tree.truncated());
    }

    @Test
    void rejectsInvalidGitHubTreePayload() throws Exception {
        JsonNode response = objectMapper.readTree("{\"tree\": {}}");

        when(gitHubApiClient.getRepositoryTree(
                "acme", "demo", "main")).thenReturn(response);

        GitHubApiException exception = assertThrows(
                GitHubApiException.class,
                () -> service.getRepositoryTree("acme", "demo", "main"));
        assertEquals(502, exception.getStatusCode());
    }
}

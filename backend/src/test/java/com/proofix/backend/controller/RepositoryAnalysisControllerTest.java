package com.proofix.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.proofix.backend.exception.GlobalExceptionHandler;
import com.proofix.backend.exception.GitHubApiException;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.github.RepositoryFileReader.ScannedFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import com.proofix.backend.service.RepositoryAnalysisOrchestrationService;
import com.proofix.backend.service.RepositoryAnalysisService.AnalysisResult;
import com.proofix.backend.service.RepositoryAnalysisService.ClassifiedRepositoryFile;
import com.proofix.backend.service.RepositoryAnalysisService.CommitMetadata;
import com.proofix.backend.service.RepositoryAnalysisService.RepositoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class RepositoryAnalysisControllerTest {

    private static final String REPOSITORY_URL =
            "https://github.com/acme/demo";
    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RepositoryAnalysisOrchestrationService
            repositoryAnalysisOrchestrationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new RepositoryAnalysisController(
                                repositoryAnalysisOrchestrationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void returnsAnalysisResponseForValidRequest() throws Exception {
        when(repositoryAnalysisOrchestrationService.analyze(
                REPOSITORY_URL, null, USER_ID))
                .thenReturn(new RepositoryAnalysisOrchestrationService
                        .AnalysisOutcome(analysisResult(), null));

        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null))
                        .content("""
                                {"repositoryUrl":"https://github.com/acme/demo"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.repositoryName", is("demo")))
                .andExpect(jsonPath("$.metadata.defaultBranch", is("main")))
                .andExpect(jsonPath("$.commit.sha", is("commit-sha")))
                .andExpect(jsonPath("$.commit.message", is("Initial commit")))
                .andExpect(jsonPath("$.commit.authorDate",
                        is("2026-01-01T00:00:00Z")))
                .andExpect(jsonPath("$.repositoryTree.truncated", is(false)))
                .andExpect(jsonPath("$.classifiedFiles[0].category",
                        is("SOURCE")))
                .andExpect(jsonPath("$.scannedFiles[0].path",
                        is("src/App.java")))
                .andExpect(jsonPath("$.bytesDownloaded", is(12)));
    }

    @Test
    void rejectsInvalidRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryUrl":"not-a-github-url"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.repositoryUrl",
                        is("Please provide a valid public GitHub repository URL")));
    }

    @Test
    void rejectsBothRepositorySources() throws Exception {
        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repositoryUrl":"https://github.com/acme/demo",
                                  "repositoryConnectionId":
                                    "22222222-2222-2222-2222-222222222222"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNeitherRepositorySource() throws Exception {
        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankRepositoryUrl() throws Exception {
        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryUrl":" "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsGitHubApiFailureFromExistingExceptionHandler()
            throws Exception {
        when(repositoryAnalysisOrchestrationService.analyze(
                REPOSITORY_URL, null, USER_ID))
                .thenThrow(new GitHubApiException(
                        "Repository not found or inaccessible",
                        404));

        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null))
                        .content("""
                                {"repositoryUrl":"https://github.com/acme/demo"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error",
                        is("Repository not found or inaccessible")));
    }

    @Test
    void invokesServiceWithRepositoryUrl() throws Exception {
        when(repositoryAnalysisOrchestrationService.analyze(
                REPOSITORY_URL, null, USER_ID))
                .thenReturn(new RepositoryAnalysisOrchestrationService
                        .AnalysisOutcome(analysisResult(), null));

        mockMvc.perform(post("/api/repository/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null))
                        .content("""
                                {"repositoryUrl":"https://github.com/acme/demo"}
                                """))
                .andExpect(status().isOk());

        verify(repositoryAnalysisOrchestrationService).analyze(
                REPOSITORY_URL, null, USER_ID);
    }

    private AnalysisResult analysisResult() {
        RepositoryMetadata metadata = new RepositoryMetadata(
                "demo",
                "acme",
                "Demo repository",
                REPOSITORY_URL,
                "main",
                "Java",
                "public",
                false,
                7,
                2);
        RepositoryTree tree = new RepositoryTree(
                List.of(new RepositoryFile(
                        "src/App.java",
                        "blob",
                        20,
                        "sha")),
                0,
                false);
        ClassifiedRepositoryFile classifiedFile =
                new ClassifiedRepositoryFile(
                        "src/App.java",
                        "blob",
                        20,
                        "sha",
                        FileCategory.SOURCE);
        ScannedFile scannedFile = new ScannedFile(
                "src/App.java",
                FileCategory.SOURCE,
                "class App {}",
                false);

        return new AnalysisResult(
                metadata,
                new CommitMetadata(
                        "commit-sha",
                        "Initial commit",
                        "2026-01-01T00:00:00Z"),
                tree,
                List.of(classifiedFile),
                List.of(scannedFile),
                0,
                0,
                false,
                12);
    }
}

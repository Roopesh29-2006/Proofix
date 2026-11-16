package com.proofix.backend.service;

import java.util.List;

import com.proofix.backend.exception.GitHubApiException;
import com.proofix.backend.exception.RepositoryAccessDeniedException;
import com.proofix.backend.github.GitHubUrlParser;
import com.proofix.backend.github.GitHubUrlParser.RepositoryCoordinates;
import com.proofix.backend.github.RepositoryFileClassifier;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.github.RepositoryFileReader;
import com.proofix.backend.github.RepositoryFileReader.FileReadResult;
import com.proofix.backend.github.RepositoryMetadataService;
import com.proofix.backend.github.RepositoryTreeService;
import com.proofix.backend.github.RepositoryTreeService.RepositoryFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class RepositoryAnalysisService {

    static final String PRIVATE_REPOSITORY_MESSAGE =
            "Private repositories require a project repository connection.";

    private final GitHubUrlParser gitHubUrlParser;
    private final RepositoryMetadataService repositoryMetadataService;
    private final RepositoryTreeService repositoryTreeService;
    private final RepositoryFileClassifier repositoryFileClassifier;
    private final RepositoryFileReader repositoryFileReader;

    public RepositoryAnalysisService(
            GitHubUrlParser gitHubUrlParser,
            RepositoryMetadataService repositoryMetadataService,
            RepositoryTreeService repositoryTreeService,
            RepositoryFileClassifier repositoryFileClassifier,
            RepositoryFileReader repositoryFileReader) {
        this.gitHubUrlParser = gitHubUrlParser;
        this.repositoryMetadataService = repositoryMetadataService;
        this.repositoryTreeService = repositoryTreeService;
        this.repositoryFileClassifier = repositoryFileClassifier;
        this.repositoryFileReader = repositoryFileReader;
    }

    /**
     * Analyzes a repository without a visibility check. Only for the
     * connection-based path, where ownership has already been verified.
     */
    public AnalysisResult analyze(String repositoryUrl) {
        return analyze(repositoryUrl, false);
    }

    /**
     * One-off URL analysis: only public repositories are allowed. The
     * {@code private} flag is checked from the repository metadata before
     * the commit, tree or any file content is requested; a missing or
     * non-boolean flag is rejected.
     */
    public AnalysisResult analyzePublicRepository(String repositoryUrl) {
        return analyze(repositoryUrl, true);
    }

    /**
     * Checks that a repository is public using only the metadata call (no
     * commit, tree or file requests). Same fail-closed rule as
     * {@link #analyzePublicRepository}.
     *
     * @throws RepositoryAccessDeniedException if it is not public
     */
    public void verifyPublicRepository(String repositoryUrl) {
        RepositoryCoordinates coordinates = gitHubUrlParser.parse(repositoryUrl);
        requirePublicRepository(
                repositoryMetadataService.getRepositoryMetadata(coordinates));
    }

    private AnalysisResult analyze(String repositoryUrl, boolean publicOnly) {
        RepositoryCoordinates coordinates = gitHubUrlParser.parse(repositoryUrl);
        JsonNode metadataResponse =
                repositoryMetadataService.getRepositoryMetadata(coordinates);
        if (publicOnly) {
            requirePublicRepository(metadataResponse);
        }
        RepositoryMetadata metadata = extractMetadata(metadataResponse);
        JsonNode commitResponse =
                repositoryMetadataService.getRepositoryLatestCommit(
                        coordinates,
                        metadata.defaultBranch());
        CommitMetadata commit = extractCommitMetadata(commitResponse);

        RepositoryTree tree = repositoryTreeService.getRepositoryTree(
                coordinates,
                metadata.defaultBranch());

        List<ClassifiedRepositoryFile> classifiedFiles = tree.entries().stream()
                .map(this::classify)
                .toList();

        FileReadResult fileReadResult =
                repositoryFileReader.readRelevantFiles(
                        coordinates,
                        tree,
                        metadata.defaultBranch());

        return new AnalysisResult(
                metadata,
                commit,
                tree,
                classifiedFiles,
                fileReadResult.files(),
                tree.filesSkipped(),
                fileReadResult.filesSkipped(),
                tree.truncated(),
                fileReadResult.bytesDownloaded());
    }

    /**
     * Fails closed: the repository must report {@code "private": false}, and
     * a {@code visibility} field, when present, must be {@code "public"}.
     */
    private static void requirePublicRepository(JsonNode metadata) {
        JsonNode privateFlag = metadata == null || !metadata.isObject()
                ? null
                : metadata.get("private");
        boolean isPublic = privateFlag != null
                && privateFlag.isBoolean()
                && !privateFlag.booleanValue();

        JsonNode visibility = isPublic ? metadata.get("visibility") : null;
        if (visibility != null && !visibility.isNull()
                && !(visibility.isTextual()
                        && "public".equals(visibility.asText()))) {
            isPublic = false;
        }

        if (!isPublic) {
            throw new RepositoryAccessDeniedException(
                    PRIVATE_REPOSITORY_MESSAGE);
        }
    }

    private static CommitMetadata extractCommitMetadata(JsonNode commit) {
        if (commit == null || !commit.isObject()) {
            throw new GitHubApiException(
                    "GitHub returned invalid repository commit metadata.",
                    502);
        }

        String sha = requiredText(
                commit,
                "sha",
                "GitHub repository commit metadata did not include a commit SHA.");

        return new CommitMetadata(
                sha,
                optionalText(commit.path("commit"), "message"),
                optionalText(
                        commit.path("commit").path("author"),
                        "date"));
    }

    private ClassifiedRepositoryFile classify(RepositoryFile file) {
        return new ClassifiedRepositoryFile(
                file.path(),
                file.type(),
                file.size(),
                file.sha(),
                repositoryFileClassifier.classify(file.path()));
    }

    private static RepositoryMetadata extractMetadata(JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            throw new GitHubApiException(
                    "GitHub returned invalid repository metadata.",
                    502);
        }

        String defaultBranch = requiredText(
                metadata,
                "default_branch",
                "GitHub repository metadata did not include a default branch.");

        return new RepositoryMetadata(
                optionalText(metadata, "name"),
                optionalText(metadata.path("owner"), "login"),
                optionalText(metadata, "description"),
                optionalText(metadata, "html_url"),
                defaultBranch,
                optionalText(metadata, "language"),
                optionalText(metadata, "visibility"),
                optionalBoolean(metadata, "fork"),
                optionalInteger(metadata, "stargazers_count"),
                optionalInteger(metadata, "forks_count"));
    }

    private static String requiredText(
            JsonNode object,
            String field,
            String message) {
        String value = optionalText(object, field);
        if (value == null || value.isBlank()) {
            throw new GitHubApiException(message, 502);
        }
        return value;
    }

    private static String optionalText(JsonNode object, String field) {
        if (object == null) {
            return null;
        }

        JsonNode value = object.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Boolean optionalBoolean(JsonNode object, String field) {
        if (object == null) {
            return null;
        }

        JsonNode value = object.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    private static Integer optionalInteger(JsonNode object, String field) {
        if (object == null) {
            return null;
        }

        JsonNode value = object.path(field);
        return value.isIntegralNumber() ? value.asInt() : null;
    }

    public record RepositoryMetadata(
            String repositoryName,
            String ownerLogin,
            String description,
            String htmlUrl,
            String defaultBranch,
            String primaryLanguage,
            String visibility,
            Boolean fork,
            Integer stars,
            Integer forks) {}

    public record ClassifiedRepositoryFile(
            String path,
            String type,
            long size,
            String sha,
            FileCategory category) {}

    public record CommitMetadata(
            String sha,
            String message,
            String authorDate) {}

    public record AnalysisResult(
            RepositoryMetadata metadata,
            CommitMetadata commit,
            RepositoryTree repositoryTree,
            List<ClassifiedRepositoryFile> classifiedFiles,
            List<RepositoryFileReader.ScannedFile> scannedFiles,
            int treeEntriesSkipped,
            int filesSkipped,
            boolean treeTruncated,
            long bytesDownloaded) {}
}

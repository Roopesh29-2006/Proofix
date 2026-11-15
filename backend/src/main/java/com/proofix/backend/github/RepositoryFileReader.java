package com.proofix.backend.github;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.proofix.backend.exception.GitHubApiException;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.github.RepositoryTreeService.RepositoryFile;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import com.proofix.backend.redaction.SecretRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class RepositoryFileReader {

    private final GitHubApiClient gitHubApiClient;
    private final RepositoryFileClassifier fileClassifier;
    private final SecretRedactor secretRedactor;
    private final int maxFiles;
    private final long maxFileSizeBytes;
    private final long maxTotalContentBytes;

    public RepositoryFileReader(
            GitHubApiClient gitHubApiClient,
            RepositoryFileClassifier fileClassifier,
            SecretRedactor secretRedactor,
            @Value("${github.scan.max-files:100}") int maxFiles,
            @Value("${github.scan.max-file-size-bytes:1048576}")
            long maxFileSizeBytes,
            @Value("${github.scan.max-total-content-bytes:20971520}")
            long maxTotalContentBytes) {

        if (maxFiles <= 0
                || maxFileSizeBytes <= 0
                || maxTotalContentBytes <= 0) {
            throw new IllegalArgumentException(
                    "Repository scan limits must be greater than zero");
        }
        if (secretRedactor == null) {
            throw new IllegalArgumentException("Secret redactor is required");
        }

        this.gitHubApiClient = gitHubApiClient;
        this.fileClassifier = fileClassifier;
        this.secretRedactor = secretRedactor;
        this.maxFiles = maxFiles;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxTotalContentBytes = maxTotalContentBytes;
    }

    public FileReadResult readRelevantFiles(
            GitHubUrlParser.RepositoryCoordinates coordinates,
            RepositoryTree repositoryTree,
            String reference) {

        if (coordinates == null) {
            throw new IllegalArgumentException(
                    "Repository coordinates are required");
        }
        if (repositoryTree == null) {
            throw new IllegalArgumentException(
                    "Repository tree is required");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository reference is required");
        }

        List<ScannedFile> files = new ArrayList<>();
        Set<String> downloadedPaths = new HashSet<>();
        long totalBytes = 0;
        int filesSkipped = 0;

        for (RepositoryFile repositoryFile : repositoryTree.entries()) {
            if (files.size() >= maxFiles
                    || totalBytes >= maxTotalContentBytes) {
                filesSkipped++;
                continue;
            }

            if (!"blob".equals(repositoryFile.type())
                    || !downloadedPaths.add(repositoryFile.path())
                    || !isRelevant(repositoryFile.path())) {
                filesSkipped++;
                continue;
            }

            if (repositoryFile.size() < 0
                    || repositoryFile.size() > maxFileSizeBytes
                    || repositoryFile.size()
                    > maxTotalContentBytes - totalBytes) {
                filesSkipped++;
                continue;
            }

            JsonNode response = readFile(
                    coordinates,
                    repositoryFile.path(),
                    reference);

            byte[] contentBytes = decodeContent(response);

            if (contentBytes == null
                    || contentBytes.length > maxFileSizeBytes
                    || contentBytes.length
                    > maxTotalContentBytes - totalBytes
                    || isBinary(contentBytes)) {
                filesSkipped++;
                continue;
            }

            String content = decodeText(contentBytes);
            if (content == null) {
                filesSkipped++;
                continue;
            }

            SecretRedactor.RedactionResult redaction =
                    secretRedactor.redact(repositoryFile.path(), content);

            files.add(new ScannedFile(
                    repositoryFile.path(),
                    fileClassifier.classify(repositoryFile.path()),
                    redaction.content(),
                    redaction.redacted()));
            totalBytes += contentBytes.length;
        }

        return new FileReadResult(
                List.copyOf(files),
                filesSkipped,
                totalBytes);
    }

    private JsonNode readFile(
            GitHubUrlParser.RepositoryCoordinates coordinates,
            String path,
            String reference) {

        try {
            return gitHubApiClient.getRepositoryFile(
                    coordinates.owner(),
                    coordinates.repository(),
                    path,
                    reference);
        } catch (GitHubApiException exception) {
            if (exception.getStatusCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    private static byte[] decodeContent(JsonNode response) {
        if (response == null
                || !"file".equals(response.path("type").asText())
                || !"base64".equalsIgnoreCase(
                        response.path("encoding").asText())
                || response.path("content").isMissingNode()) {
            return null;
        }

        String encodedContent = response.path("content").asText()
                .replaceAll("\\s", "");

        try {
            return java.util.Base64.getDecoder().decode(encodedContent);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isBinary(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static String decodeText(byte[] content) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private boolean isRelevant(String path) {
        FileCategory category = fileClassifier.classify(path);
        return category == FileCategory.SOURCE
                || category == FileCategory.DEPENDENCY_MANIFEST
                || category == FileCategory.CONFIGURATION
                || category == FileCategory.DEPLOYMENT;
    }

    public record ScannedFile(
            String path,
            FileCategory category,
            String content,
            boolean secretsRedacted) {}

    public record FileReadResult(
            List<ScannedFile> files,
            int filesSkipped,
            long bytesDownloaded) {}
}

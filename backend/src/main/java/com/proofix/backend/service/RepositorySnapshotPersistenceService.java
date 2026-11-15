package com.proofix.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.entity.RepositorySnapshotFile;
import com.proofix.backend.github.RepositoryFileReader.ScannedFile;
import com.proofix.backend.repository.RepositorySnapshotFileRepository;
import com.proofix.backend.repository.RepositorySnapshotRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RepositorySnapshotPersistenceService {

    private static final String SNAPSHOT_COMMIT_UNIQUE_CONSTRAINT =
            "uq_repository_snapshot_commit";

    private final RepositorySnapshotRepository repositorySnapshotRepository;
    private final RepositorySnapshotFileRepository
            repositorySnapshotFileRepository;
    private final TransactionTemplate transactionTemplate;

    public RepositorySnapshotPersistenceService(
            RepositorySnapshotRepository repositorySnapshotRepository,
            RepositorySnapshotFileRepository repositorySnapshotFileRepository,
            PlatformTransactionManager transactionManager) {
        this.repositorySnapshotRepository = repositorySnapshotRepository;
        this.repositorySnapshotFileRepository =
                repositorySnapshotFileRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RepositorySnapshot persist(
            ProjectRepositoryConnection repositoryConnection,
            RepositoryAnalysisService.AnalysisResult analysisResult) {

        validateInputs(repositoryConnection, analysisResult);

        UUID repositoryConnectionId =
                repositoryConnection.getRepositoryConnectionId();
        String commitSha = analysisResult.commit().sha();

        return repositorySnapshotRepository
                .findByRepositoryConnectionIdAndCommitSha(
                        repositoryConnectionId,
                        commitSha)
                .orElseGet(() -> persistNewOrReuseExisting(
                        repositoryConnectionId,
                        analysisResult));
    }

    private RepositorySnapshot persistNewOrReuseExisting(
            UUID repositoryConnectionId,
            RepositoryAnalysisService.AnalysisResult analysisResult) {

        OffsetDateTime commitAuthorDate = parseAuthorDate(
                analysisResult.commit().authorDate());

        try {
            RepositorySnapshot snapshot = transactionTemplate.execute(
                    status -> saveNewSnapshot(
                            repositoryConnectionId,
                            analysisResult,
                            commitAuthorDate));

            if (snapshot == null) {
                throw new IllegalStateException(
                        "Snapshot persistence returned no snapshot");
            }

            return snapshot;
        } catch (DataIntegrityViolationException exception) {
            if (!isSnapshotCommitUniqueViolation(exception)) {
                throw exception;
            }

            return repositorySnapshotRepository
                    .findByRepositoryConnectionIdAndCommitSha(
                            repositoryConnectionId,
                            analysisResult.commit().sha())
                    .orElseThrow(() -> exception);
        }
    }

    private static boolean isSnapshotCommitUniqueViolation(
            DataIntegrityViolationException exception) {

        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return SNAPSHOT_COMMIT_UNIQUE_CONSTRAINT.equals(
                        violation.getConstraintName());
            }
            cause = cause.getCause();
        }

        return false;
    }

    private RepositorySnapshot saveNewSnapshot(
            UUID repositoryConnectionId,
            RepositoryAnalysisService.AnalysisResult analysisResult,
            OffsetDateTime commitAuthorDate) {

        RepositorySnapshot snapshot = new RepositorySnapshot();
        snapshot.setRepositoryConnectionId(repositoryConnectionId);
        snapshot.setCommitSha(analysisResult.commit().sha());
        snapshot.setBranchName(analysisResult.metadata().defaultBranch());
        snapshot.setCommitMessage(analysisResult.commit().message());
        snapshot.setCommitAuthorDate(commitAuthorDate);
        snapshot.setRepositoryMetadata(analysisResult.metadata());
        snapshot.setFileCount(analysisResult.scannedFiles().size());
        snapshot.setBytesDownloaded(analysisResult.bytesDownloaded());

        RepositorySnapshot savedSnapshot =
                repositorySnapshotRepository.save(snapshot);

        Map<String, ScannedFile> scannedFilesByPath = new HashMap<>();
        for (ScannedFile scannedFile : analysisResult.scannedFiles()) {
            scannedFilesByPath.put(scannedFile.path(), scannedFile);
        }

        List<RepositorySnapshotFile> snapshotFiles =
                analysisResult.classifiedFiles().stream()
                        .map(classifiedFile -> toSnapshotFile(
                                savedSnapshot.getSnapshotId(),
                                classifiedFile,
                                scannedFilesByPath.get(
                                        classifiedFile.path())))
                        .toList();

        repositorySnapshotFileRepository.saveAll(snapshotFiles);
        return savedSnapshot;
    }

    private static RepositorySnapshotFile toSnapshotFile(
            UUID snapshotId,
            RepositoryAnalysisService.ClassifiedRepositoryFile classifiedFile,
            ScannedFile scannedFile) {

        RepositorySnapshotFile snapshotFile = new RepositorySnapshotFile();
        snapshotFile.setSnapshotId(snapshotId);
        snapshotFile.setFilePath(classifiedFile.path());
        snapshotFile.setGithubSha(classifiedFile.sha());
        snapshotFile.setFileType(classifiedFile.type());
        snapshotFile.setFileCategory(classifiedFile.category());
        snapshotFile.setFileSize(classifiedFile.size());

        if (scannedFile != null) {
            snapshotFile.setScannedContent(scannedFile.content());
            snapshotFile.setContentHash(sha256(scannedFile.content()));
            snapshotFile.setSecretsRedacted(scannedFile.secretsRedacted());
        }

        return snapshotFile;
    }

    private static OffsetDateTime parseAuthorDate(String authorDate) {
        if (authorDate == null) {
            return null;
        }
        if (authorDate.isBlank()) {
            throw new IllegalArgumentException(
                    "Commit author timestamp cannot be blank");
        }

        try {
            return OffsetDateTime.parse(authorDate);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Invalid commit author timestamp",
                    exception);
        }
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception);
        }
    }

    private static void validateInputs(
            ProjectRepositoryConnection repositoryConnection,
            RepositoryAnalysisService.AnalysisResult analysisResult) {

        if (repositoryConnection == null
                || repositoryConnection.getRepositoryConnectionId() == null) {
            throw new IllegalArgumentException(
                    "An ownership-validated repository connection is required");
        }
        if (analysisResult == null
                || analysisResult.metadata() == null
                || analysisResult.commit() == null
                || analysisResult.classifiedFiles() == null
                || analysisResult.scannedFiles() == null) {
            throw new IllegalArgumentException(
                    "A complete repository analysis result is required");
        }
        if (analysisResult.commit().sha() == null
                || analysisResult.commit().sha().isBlank()) {
            throw new IllegalArgumentException(
                    "Repository commit SHA is required");
        }
        if (analysisResult.bytesDownloaded() < 0) {
            throw new IllegalArgumentException(
                    "Downloaded bytes cannot be negative");
        }
    }
}

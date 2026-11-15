package com.proofix.backend.service;

import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotFileResponse;
import com.proofix.backend.dto.RepositorySnapshotFileResponse.FileMetadata;
import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.entity.RepositorySnapshotFile;
import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.repository.ProjectRepository;
import com.proofix.backend.repository.ProjectRepositoryConnectionRepository;
import com.proofix.backend.repository.RepositorySnapshotFileRepository;
import com.proofix.backend.repository.RepositorySnapshotRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class RepositorySnapshotFileRetrievalService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final String SNAPSHOT_NOT_FOUND =
            "Repository snapshot not found";

    private final RepositorySnapshotRepository snapshotRepository;
    private final RepositorySnapshotFileRepository snapshotFileRepository;
    private final ProjectRepositoryConnectionRepository
            connectionRepository;
    private final ProjectRepository projectRepository;

    public RepositorySnapshotFileRetrievalService(
            RepositorySnapshotRepository snapshotRepository,
            RepositorySnapshotFileRepository snapshotFileRepository,
            ProjectRepositoryConnectionRepository connectionRepository,
            ProjectRepository projectRepository) {
        this.snapshotRepository = snapshotRepository;
        this.snapshotFileRepository = snapshotFileRepository;
        this.connectionRepository = connectionRepository;
        this.projectRepository = projectRepository;
    }

    public RepositorySnapshotFileResponse getFiles(
            UUID snapshotId,
            UUID userId,
            Pageable pageable,
            FileCategory category,
            boolean includeContent) {

        validatePageable(pageable);
        RepositorySnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        SNAPSHOT_NOT_FOUND));

        ProjectRepositoryConnection connection =
                connectionRepository
                        .findByRepositoryConnectionIdAndUserId(
                                snapshot.getRepositoryConnectionId(), userId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                SNAPSHOT_NOT_FOUND));

        if (connection.getRevokedAt() != null
                || connection.getProjectId() == null
                || projectRepository
                        .findByProjectIdAndUserIdAndDeletedAtIsNull(
                                connection.getProjectId(), userId)
                        .isEmpty()) {
            throw new ResourceNotFoundException(SNAPSHOT_NOT_FOUND);
        }

        Page<RepositorySnapshotFile> files = category == null
                ? snapshotFileRepository
                        .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                                snapshotId, pageable)
                : snapshotFileRepository
                        .findBySnapshotIdAndFileCategoryOrderByFilePathAscSnapshotFileIdAsc(
                                snapshotId, category, pageable);

        return new RepositorySnapshotFileResponse(
                files.getContent().stream()
                        .map(file -> toMetadata(file, includeContent))
                        .toList(),
                files.getNumber(),
                files.getSize(),
                files.getTotalElements(),
                files.getTotalPages());
    }

    private static FileMetadata toMetadata(
            RepositorySnapshotFile file,
            boolean includeContent) {
        String scannedContent = includeContent
                && "blob".equals(file.getFileType())
                ? file.getScannedContent()
                : null;

        return new FileMetadata(
                file.getSnapshotFileId(),
                file.getFilePath(),
                file.getGithubSha(),
                file.getFileType(),
                file.getFileCategory(),
                file.getFileSize(),
                file.getContentHash(),
                file.getSecretsRedacted(),
                scannedContent);
    }

    private static void validatePageable(Pageable pageable) {
        if (pageable == null) {
            throw new BadRequestException("Pagination is required");
        }
        if (pageable.getPageNumber() < 0) {
            throw new BadRequestException("Page must not be negative");
        }
        if (pageable.getPageSize() < 1
                || pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BadRequestException(
                    "Page size must be between 1 and 50");
        }
    }
}

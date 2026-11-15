package com.proofix.backend.service;

import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotHistoryResponse;
import com.proofix.backend.dto.RepositorySnapshotHistoryResponse.SnapshotMetadata;
import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.repository.ProjectRepository;
import com.proofix.backend.repository.ProjectRepositoryConnectionRepository;
import com.proofix.backend.repository.RepositorySnapshotRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class RepositorySnapshotHistoryService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final String CONNECTION_NOT_FOUND =
            "Repository connection not found";

    private final ProjectRepositoryConnectionRepository
            projectRepositoryConnectionRepository;
    private final ProjectRepository projectRepository;
    private final RepositorySnapshotRepository repositorySnapshotRepository;

    public RepositorySnapshotHistoryService(
            ProjectRepositoryConnectionRepository
                    projectRepositoryConnectionRepository,
            ProjectRepository projectRepository,
            RepositorySnapshotRepository repositorySnapshotRepository) {
        this.projectRepositoryConnectionRepository =
                projectRepositoryConnectionRepository;
        this.projectRepository = projectRepository;
        this.repositorySnapshotRepository = repositorySnapshotRepository;
    }

    public RepositorySnapshotHistoryResponse getHistory(
            UUID repositoryConnectionId,
            UUID userId,
            Pageable pageable) {

        validatePageable(pageable);
        ProjectRepositoryConnection connection =
                projectRepositoryConnectionRepository
                        .findByRepositoryConnectionIdAndUserId(
                                repositoryConnectionId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                CONNECTION_NOT_FOUND));

        if (connection.getRevokedAt() != null
                || connection.getProjectId() == null
                || projectRepository
                        .findByProjectIdAndUserIdAndDeletedAtIsNull(
                                connection.getProjectId(), userId)
                        .isEmpty()) {
            throw new ResourceNotFoundException(CONNECTION_NOT_FOUND);
        }

        Page<RepositorySnapshot> snapshots =
                repositorySnapshotRepository
                        .findAllByRepositoryConnectionIdOrderByAnalyzedAtDescSnapshotIdDesc(
                                repositoryConnectionId, pageable);

        return new RepositorySnapshotHistoryResponse(
                snapshots.getContent().stream()
                        .map(RepositorySnapshotHistoryService::toMetadata)
                        .toList(),
                snapshots.getNumber(),
                snapshots.getSize(),
                snapshots.getTotalElements(),
                snapshots.getTotalPages());
    }

    public SnapshotMetadata getSnapshot(UUID snapshotId, UUID userId) {
        RepositorySnapshot snapshot = repositorySnapshotRepository
                .findById(snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository snapshot not found"));

        ProjectRepositoryConnection connection =
                projectRepositoryConnectionRepository
                        .findByRepositoryConnectionIdAndUserId(
                                snapshot.getRepositoryConnectionId(), userId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Repository snapshot not found"));

        if (connection.getRevokedAt() != null
                || connection.getProjectId() == null
                || projectRepository
                        .findByProjectIdAndUserIdAndDeletedAtIsNull(
                                connection.getProjectId(), userId)
                        .isEmpty()) {
            throw new ResourceNotFoundException(
                    "Repository snapshot not found");
        }

        return toMetadata(snapshot);
    }

    private static SnapshotMetadata toMetadata(RepositorySnapshot snapshot) {
        return new SnapshotMetadata(
                snapshot.getSnapshotId(),
                snapshot.getCommitSha(),
                snapshot.getBranchName(),
                snapshot.getCommitMessage(),
                snapshot.getCommitAuthorDate(),
                snapshot.getRepositoryMetadata(),
                snapshot.getFileCount(),
                snapshot.getBytesDownloaded(),
                snapshot.getAnalyzedAt());
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

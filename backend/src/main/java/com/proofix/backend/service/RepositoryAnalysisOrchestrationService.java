package com.proofix.backend.service;

import java.util.UUID;

import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.repository.ProjectRepositoryConnectionRepository;
import com.proofix.backend.repository.ProjectRepository;
import org.springframework.stereotype.Service;

@Service
public class RepositoryAnalysisOrchestrationService {

    private static final String CONNECTION_NOT_FOUND =
            "Repository connection not found";

    private final RepositoryAnalysisService repositoryAnalysisService;
    private final RepositorySnapshotPersistenceService
            repositorySnapshotPersistenceService;
    private final ProjectRepositoryConnectionRepository
            projectRepositoryConnectionRepository;
    private final ProjectRepository projectRepository;

    public RepositoryAnalysisOrchestrationService(
            RepositoryAnalysisService repositoryAnalysisService,
            RepositorySnapshotPersistenceService
                    repositorySnapshotPersistenceService,
            ProjectRepositoryConnectionRepository
                    projectRepositoryConnectionRepository,
            ProjectRepository projectRepository) {
        this.repositoryAnalysisService = repositoryAnalysisService;
        this.repositorySnapshotPersistenceService =
                repositorySnapshotPersistenceService;
        this.projectRepositoryConnectionRepository =
                projectRepositoryConnectionRepository;
        this.projectRepository = projectRepository;
    }

    public AnalysisOutcome analyze(
            String repositoryUrl,
            UUID repositoryConnectionId,
            UUID userId) {

        if (repositoryConnectionId == null) {
            return new AnalysisOutcome(
                    repositoryAnalysisService.analyze(repositoryUrl),
                    null);
        }

        ProjectRepositoryConnection ownedConnection =
                projectRepositoryConnectionRepository
                        .findByRepositoryConnectionIdAndUserId(
                                repositoryConnectionId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                CONNECTION_NOT_FOUND));

        if (ownedConnection.getRevokedAt() != null
                || ownedConnection.getProjectId() == null
                || !projectRepository
                        .findByProjectIdAndUserIdAndDeletedAtIsNull(
                                ownedConnection.getProjectId(), userId)
                        .isPresent()) {
            throw new ResourceNotFoundException(CONNECTION_NOT_FOUND);
        }

        RepositoryAnalysisService.AnalysisResult result =
                repositoryAnalysisService.analyze(
                        ownedConnection.getRepositoryUrl());
        RepositorySnapshot snapshot =
                repositorySnapshotPersistenceService.persist(
                        ownedConnection, result);

        return new AnalysisOutcome(result, snapshot.getSnapshotId());
    }

    public record AnalysisOutcome(
            RepositoryAnalysisService.AnalysisResult result,
            UUID snapshotId) {}
}

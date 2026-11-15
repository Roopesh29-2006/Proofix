package com.proofix.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.proofix.backend.entity.ProjectRepositoryConnection;

public interface ProjectRepositoryConnectionRepository
        extends JpaRepository<ProjectRepositoryConnection, UUID> {

    Optional<ProjectRepositoryConnection>
    findByRepositoryConnectionIdAndUserId(
            UUID repositoryConnectionId,
            UUID userId);

    Optional<ProjectRepositoryConnection>
    findByRepositoryConnectionIdAndProjectIdAndUserId(
            UUID repositoryConnectionId,
            UUID projectId,
            UUID userId);
}

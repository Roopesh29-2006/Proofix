package com.proofix.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.proofix.backend.entity.RepositorySnapshot;

public interface RepositorySnapshotRepository
        extends JpaRepository<RepositorySnapshot, UUID> {

    Optional<RepositorySnapshot> findByRepositoryConnectionIdAndCommitSha(
            UUID repositoryConnectionId,
            String commitSha);

    Page<RepositorySnapshot>
    findAllByRepositoryConnectionIdOrderByAnalyzedAtDescSnapshotIdDesc(
            UUID repositoryConnectionId,
            Pageable pageable);
}

package com.proofix.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.proofix.backend.entity.RepositorySnapshotFile;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;

public interface RepositorySnapshotFileRepository
        extends JpaRepository<RepositorySnapshotFile, UUID> {

    Optional<RepositorySnapshotFile> findBySnapshotIdAndFilePath(
            UUID snapshotId,
            String filePath);

    Page<RepositorySnapshotFile>
    findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
            UUID snapshotId,
            Pageable pageable);

    Page<RepositorySnapshotFile>
    findBySnapshotIdAndFileCategoryOrderByFilePathAscSnapshotFileIdAsc(
            UUID snapshotId,
            FileCategory fileCategory,
            Pageable pageable);
}

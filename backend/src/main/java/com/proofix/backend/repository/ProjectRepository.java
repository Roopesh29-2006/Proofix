package com.proofix.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.proofix.backend.entity.Project;


public interface ProjectRepository extends JpaRepository<Project, UUID> {

      // Retrieve all active, non-deleted projects belonging to a user
    List<Project> findAllByUserIdAndDeletedAtIsNull(UUID userId);

    // Retrieve one project belonging to a specific user
    Optional<Project> findByProjectIdAndUserIdAndDeletedAtIsNull(
            UUID projectId,
            UUID userId
    );

    // Check whether a project name already exists for this user
    boolean existsByUserIdAndProjectNameIgnoreCaseAndDeletedAtIsNull(
            UUID userId,
            String projectName
    );
    
}

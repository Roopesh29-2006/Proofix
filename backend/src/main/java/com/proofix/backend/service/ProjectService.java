
package com.proofix.backend.service;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.proofix.backend.dto.ProjectCreateRequest;
import com.proofix.backend.dto.ProjectCreateResponse;
import com.proofix.backend.dto.ProjectUpdateRequest;
import com.proofix.backend.entity.Project;
import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.repository.ProjectRepository;


@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ProjectCreateResponse createProject(ProjectCreateRequest request) {

        // 1. Get authenticated user from Spring Security
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal().equals("anonymousUser")) {
            throw new AuthenticationCredentialsNotFoundException(
                    "User authentication is required");
        }

        UUID userId = UUID.fromString(authentication.getName());

        // 2. Normalize project name
        String projectName = request.getProjectName()
                .trim()
                .replaceAll("\\s+", " ");

        // 3. Check for duplicate project name
        if (projectRepository
                .existsByUserIdAndProjectNameIgnoreCaseAndDeletedAtIsNull(
                        userId, projectName)) {

            throw new ConflictException(
                    "A project with this name already exists");
        }

        // 4. Create project entity
        Project project = new Project();

        project.setUserId(userId);
        project.setProjectName(projectName);
        project.setDescription(
                request.getDescription() == null
                        ? null
                        : request.getDescription().trim()
        );

        project.setProjectStatus(Project.ProjectStatus.ACTIVE);

        // 5. Save project in PostgreSQL
        Project savedProject = projectRepository.saveAndFlush(project);

        // 6. Return response DTO
        return new ProjectCreateResponse(
                savedProject.getProjectId().toString(),
                savedProject.getProjectName(),
                savedProject.getDescription(),
                savedProject.getProjectStatus().name(),
                savedProject.getCreatedAt(),
                savedProject.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
public List<ProjectCreateResponse> getAllProjects() {

    // 1. Get authenticated user
    Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null || !authentication.isAuthenticated()
            || authentication.getPrincipal().equals("anonymousUser")) {
        throw new AuthenticationCredentialsNotFoundException(
                "User authentication is required");
    }

    UUID userId = UUID.fromString(authentication.getName());

    // 2. Retrieve all active (non-deleted) projects
    List<Project> projects =
            projectRepository.findAllByUserIdAndDeletedAtIsNull(userId);

    // 3. Convert entities into response DTOs
    return projects.stream()
            .map(project -> new ProjectCreateResponse(
                    project.getProjectId().toString(),
                    project.getProjectName(),
                    project.getDescription(),
                    project.getProjectStatus().name(),
                    project.getCreatedAt(),
                    project.getUpdatedAt()
            ))
            .toList();
}

   
@Transactional
public ProjectCreateResponse updateProject(
        UUID projectId,
        ProjectUpdateRequest request) {

    // 1. Get authenticated user
    Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null || !authentication.isAuthenticated()
            || authentication.getPrincipal().equals("anonymousUser")) {
        throw new AuthenticationCredentialsNotFoundException(
                "User authentication is required");
    }

    UUID userId = UUID.fromString(authentication.getName());

    // 2. Find project belonging to this user
    Project project = projectRepository
            .findByProjectIdAndUserIdAndDeletedAtIsNull(
                    projectId, userId)
            .orElseThrow(() ->
                    new ResourceNotFoundException("Project not found"));

    // 3. Normalize project name
    String projectName = request.getProjectName()
            .trim()
            .replaceAll("\\s+", " ");

    // 4. Check whether another project has the same name
    boolean duplicateName =
            projectRepository
                    .existsByUserIdAndProjectNameIgnoreCaseAndDeletedAtIsNull(
                            userId, projectName)
            && !project.getProjectName().equalsIgnoreCase(projectName);

    if (duplicateName) {
        throw new ConflictException(
                "A project with this name already exists");
    }

    // 5. Update project details
    project.setProjectName(projectName);

    project.setDescription(
            request.getDescription() == null
                    ? null
                    : request.getDescription().trim()
    );

    // 6. Save updated project
    Project updatedProject = projectRepository.saveAndFlush(project);

    // 7. Return response DTO
    return new ProjectCreateResponse(
            updatedProject.getProjectId().toString(),
            updatedProject.getProjectName(),
            updatedProject.getDescription(),
            updatedProject.getProjectStatus().name(),
            updatedProject.getCreatedAt(),
            updatedProject.getUpdatedAt()
    );
}

}

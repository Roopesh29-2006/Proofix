package com.proofix.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.proofix.backend.dto.ProjectCreateRequest;
import com.proofix.backend.dto.ProjectUpdateRequest;
import com.proofix.backend.entity.Project;
import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private ProjectRepository projectRepository;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void duplicateProjectNameOnCreateIsConflict() {
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setProjectName("  Checkout   Service ");
        when(projectRepository
                .existsByUserIdAndProjectNameIgnoreCaseAndDeletedAtIsNull(
                        USER_ID, "Checkout Service"))
                .thenReturn(true);

        ConflictException failure = assertThrows(ConflictException.class,
                () -> projectService.createProject(request));

        assertEquals("A project with this name already exists",
                failure.getMessage());
        verify(projectRepository, never()).saveAndFlush(any());
    }

    @Test
    void updatingMissingProjectIsNotFound() {
        ProjectUpdateRequest request = new ProjectUpdateRequest();
        request.setProjectName("Renamed");
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException failure = assertThrows(
                ResourceNotFoundException.class,
                () -> projectService.updateProject(PROJECT_ID, request));

        assertEquals("Project not found", failure.getMessage());
    }

    @Test
    void renamingToAnotherExistingProjectNameIsConflict() {
        Project existing = new Project();
        existing.setProjectName("Checkout Service");
        ProjectUpdateRequest request = new ProjectUpdateRequest();
        request.setProjectName("Payments");
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.of(existing));
        when(projectRepository
                .existsByUserIdAndProjectNameIgnoreCaseAndDeletedAtIsNull(
                        USER_ID, "Payments"))
                .thenReturn(true);

        assertThrows(ConflictException.class,
                () -> projectService.updateProject(PROJECT_ID, request));
        verify(projectRepository, never()).saveAndFlush(any());
    }
}

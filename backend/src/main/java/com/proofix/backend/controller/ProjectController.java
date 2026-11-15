
package com.proofix.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.List;

import com.proofix.backend.dto.ProjectCreateRequest;
import com.proofix.backend.dto.ProjectCreateResponse;
import com.proofix.backend.service.ProjectService;


import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    // Create a new project
    @PostMapping
    public ResponseEntity<ProjectCreateResponse> createProject(
            @Valid @RequestBody ProjectCreateRequest request) {

        ProjectCreateResponse response =
                projectService.createProject(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProjectCreateResponse>> getAllProjects() {

        List<ProjectCreateResponse> projects =
                projectService.getAllProjects();

        return ResponseEntity.ok(projects);
    }
}

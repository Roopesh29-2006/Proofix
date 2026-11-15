package com.proofix.backend.dto;

import java.time.OffsetDateTime;

public class ProjectCreateResponse {

    private String projectId;
    private String projectName;
    private String description;
    private String projectStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public ProjectCreateResponse(String projectId, String projectName, String description, String projectStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.description = description;
        this.projectStatus = projectStatus;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getDescription() {
        return description;
    }

    public String getProjectStatus() {
        return projectStatus;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    
    
}


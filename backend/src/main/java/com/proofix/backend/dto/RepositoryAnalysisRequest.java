package com.proofix.backend.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;

import java.util.UUID;

public class RepositoryAnalysisRequest {

    @Pattern(
        regexp = "^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?$",
        message = "Please provide a valid public GitHub repository URL"
    )
    private String repositoryUrl;

    private UUID repositoryConnectionId;

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public UUID getRepositoryConnectionId() {
        return repositoryConnectionId;
    }

    public void setRepositoryConnectionId(UUID repositoryConnectionId) {
        this.repositoryConnectionId = repositoryConnectionId;
    }

    @AssertTrue(message = "Exactly one of repositoryUrl or repositoryConnectionId must be provided")
    public boolean hasExactlyOneRepositorySource() {
        boolean hasUrl = repositoryUrl != null && !repositoryUrl.isBlank();
        return hasUrl ^ repositoryConnectionId != null;
    }
}

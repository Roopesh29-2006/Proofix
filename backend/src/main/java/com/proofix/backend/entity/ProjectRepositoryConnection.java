package com.proofix.backend.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects_repos")
public class ProjectRepositoryConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
        name = "repository_connection_id",
        nullable = false,
        updatable = false
    )
    private UUID repositoryConnectionId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "github_connection_id", nullable = false)
    private UUID githubConnectionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "repository_url", nullable = false)
    private String repositoryUrl;

    @Column(name = "repository_id", length = 100)
    private String repositoryId;

    @Column(name = "default_branch", nullable = false, length = 150)
    private String defaultBranch;

    @Column(name = "is_private", nullable = false)
    private boolean privateRepository;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false,
            updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false,
            updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    public ProjectRepositoryConnection() {
    }

    public UUID getRepositoryConnectionId() {
        return repositoryConnectionId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getGithubConnectionId() {
        return githubConnectionId;
    }

    public void setGithubConnectionId(UUID githubConnectionId) {
        this.githubConnectionId = githubConnectionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public boolean isPrivate() {
        return privateRepository;
    }

    public void setPrivate(boolean privateRepository) {
        this.privateRepository = privateRepository;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}

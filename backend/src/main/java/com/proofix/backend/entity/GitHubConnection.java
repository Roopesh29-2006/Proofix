
package com.proofix.backend.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_connections")
public class GitHubConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
        name = "github_connection_id",
        nullable = false,
        updatable = false
    )
    private UUID githubConnectionId;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "token_reference", nullable = false, length = 255)
    private String tokenReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ConnectionStatus status;

    @Column(name = "connected_at", nullable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // GitHub connection statuses
    public enum ConnectionStatus {
        CONNECTED,
        REVOKED,
        EXPIRED,
        ERROR
    }

    // Getters and setters

    public UUID getGithubConnectionId() {
        return githubConnectionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTokenReference() {
        return tokenReference;
    }

    public void setTokenReference(String tokenReference) {
        this.tokenReference = tokenReference;
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    public void setStatus(ConnectionStatus status) {
        this.status = status;
    }

    public OffsetDateTime getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(OffsetDateTime connectedAt) {
        this.connectedAt = connectedAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
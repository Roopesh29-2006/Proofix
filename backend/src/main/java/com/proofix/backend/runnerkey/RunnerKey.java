package com.proofix.backend.runnerkey;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A runner key: identifies one runner program acting for one user. Holds
 * only the hash of the key; the plaintext is never stored.
 */
@Entity
@Table(name = "runner_keys")
public class RunnerKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "name", nullable = false, length = 60, updatable = false)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true, updatable = false)
    private String keyHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    protected RunnerKey() {
    }

    RunnerKey(UUID ownerUserId, String name, String keyHash, OffsetDateTime createdAt) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.keyHash = keyHash;
        this.createdAt = createdAt;
    }

    /** Not revoked and not expired at {@code now}. */
    public boolean isActive(OffsetDateTime now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    void revoke(OffsetDateTime now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    /** Never includes the hash. */
    @Override
    public String toString() {
        return "RunnerKey[id=" + id + ", name=" + name + "]";
    }
}

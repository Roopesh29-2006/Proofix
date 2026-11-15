package com.proofix.backend.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.proofix.backend.service.RepositoryAnalysisService.RepositoryMetadata;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "repository_snapshots")
public class RepositorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(name = "repository_connection_id", nullable = false)
    private UUID repositoryConnectionId;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "branch_name", nullable = false, length = 150)
    private String branchName;

    @Column(name = "commit_message", columnDefinition = "text")
    private String commitMessage;

    @Column(name = "commit_author_date")
    private OffsetDateTime commitAuthorDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "repository_metadata", nullable = false,
            columnDefinition = "jsonb")
    private RepositoryMetadata repositoryMetadata;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "bytes_downloaded", nullable = false)
    private long bytesDownloaded;

    @Generated(event = EventType.INSERT)
    @Column(name = "analyzed_at", nullable = false, insertable = false,
            updatable = false)
    private OffsetDateTime analyzedAt;

    public RepositorySnapshot() {
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public UUID getRepositoryConnectionId() {
        return repositoryConnectionId;
    }

    public void setRepositoryConnectionId(UUID repositoryConnectionId) {
        this.repositoryConnectionId = repositoryConnectionId;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public OffsetDateTime getCommitAuthorDate() {
        return commitAuthorDate;
    }

    public void setCommitAuthorDate(OffsetDateTime commitAuthorDate) {
        this.commitAuthorDate = commitAuthorDate;
    }

    public RepositoryMetadata getRepositoryMetadata() {
        return repositoryMetadata;
    }

    public void setRepositoryMetadata(RepositoryMetadata repositoryMetadata) {
        this.repositoryMetadata = repositoryMetadata;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public long getBytesDownloaded() {
        return bytesDownloaded;
    }

    public void setBytesDownloaded(long bytesDownloaded) {
        this.bytesDownloaded = bytesDownloaded;
    }

    public OffsetDateTime getAnalyzedAt() {
        return analyzedAt;
    }
}

package com.proofix.backend.entity;

import java.util.UUID;

import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "repository_snapshot_files")
public class RepositorySnapshotFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "snapshot_file_id", nullable = false, updatable = false)
    private UUID snapshotFileId;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "file_path", nullable = false, columnDefinition = "text")
    private String filePath;

    @Column(name = "github_sha", length = 64)
    private String githubSha;

    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_category", nullable = false, length = 50)
    private FileCategory fileCategory;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "scanned_content", columnDefinition = "text")
    private String scannedContent;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "secrets_redacted")
    private Boolean secretsRedacted;

    public RepositorySnapshotFile() {
    }

    public UUID getSnapshotFileId() {
        return snapshotFileId;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(UUID snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getGithubSha() {
        return githubSha;
    }

    public void setGithubSha(String githubSha) {
        this.githubSha = githubSha;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public FileCategory getFileCategory() {
        return fileCategory;
    }

    public void setFileCategory(FileCategory fileCategory) {
        this.fileCategory = fileCategory;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getScannedContent() {
        return scannedContent;
    }

    public void setScannedContent(String scannedContent) {
        this.scannedContent = scannedContent;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Boolean getSecretsRedacted() {
        return secretsRedacted;
    }

    public void setSecretsRedacted(Boolean secretsRedacted) {
        this.secretsRedacted = secretsRedacted;
    }
}

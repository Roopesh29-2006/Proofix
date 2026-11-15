package com.proofix.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.proofix.backend.service.RepositoryAnalysisService.RepositoryMetadata;

public class RepositorySnapshotHistoryResponse {

    private final List<SnapshotMetadata> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public RepositorySnapshotHistoryResponse(
            List<SnapshotMetadata> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
    }

    public List<SnapshotMetadata> getContent() {
        return content;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public record SnapshotMetadata(
            UUID snapshotId,
            String commitSha,
            String branchName,
            String commitMessage,
            OffsetDateTime commitAuthorDate,
            RepositoryMetadata repositoryMetadata,
            int fileCount,
            long bytesDownloaded,
            OffsetDateTime analyzedAt) {}
}

package com.proofix.backend.dto;

import java.util.List;
import java.util.UUID;

import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;

public class RepositorySnapshotFileResponse {

    private final List<FileMetadata> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public RepositorySnapshotFileResponse(
            List<FileMetadata> content,
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

    public List<FileMetadata> getContent() {
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

    public record FileMetadata(
            UUID snapshotFileId,
            String filePath,
            String githubSha,
            String fileType,
            FileCategory fileCategory,
            long fileSize,
            String contentHash,
            Boolean secretsRedacted,
            String scannedContent) {}
}

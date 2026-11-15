package com.proofix.backend.dto;

import java.util.UUID;
import java.util.List;

import com.proofix.backend.github.RepositoryFileReader;
import com.proofix.backend.github.RepositoryTreeService.RepositoryTree;
import com.proofix.backend.service.RepositoryAnalysisService;

public class RepositoryAnalysisResponse {

    private RepositoryAnalysisService.RepositoryMetadata metadata;
    private RepositoryAnalysisService.CommitMetadata commit;
    private RepositoryTree repositoryTree;
    private List<RepositoryAnalysisService.ClassifiedRepositoryFile>
            classifiedFiles;
    private List<RepositoryFileReader.ScannedFile> scannedFiles;
    private int treeEntriesSkipped;
    private int filesSkipped;
    private boolean treeTruncated;
    private long bytesDownloaded;
    private UUID snapshotId;

    public RepositoryAnalysisResponse(
            RepositoryAnalysisService.AnalysisResult result) {
        this(result, null);
    }

    public RepositoryAnalysisResponse(
            RepositoryAnalysisService.AnalysisResult result,
            UUID snapshotId) {
        this.metadata = result.metadata();
        this.commit = result.commit();
        this.repositoryTree = result.repositoryTree();
        this.classifiedFiles = result.classifiedFiles();
        this.scannedFiles = result.scannedFiles();
        this.treeEntriesSkipped = result.treeEntriesSkipped();
        this.filesSkipped = result.filesSkipped();
        this.treeTruncated = result.treeTruncated();
        this.bytesDownloaded = result.bytesDownloaded();
        this.snapshotId = snapshotId;
    }

    public RepositoryAnalysisService.RepositoryMetadata getMetadata() {
        return metadata;
    }

    public RepositoryAnalysisService.CommitMetadata getCommit() {
        return commit;
    }

    public RepositoryTree getRepositoryTree() {
        return repositoryTree;
    }

    public List<RepositoryAnalysisService.ClassifiedRepositoryFile>
            getClassifiedFiles() {
        return classifiedFiles;
    }

    public List<RepositoryFileReader.ScannedFile> getScannedFiles() {
        return scannedFiles;
    }

    public int getTreeEntriesSkipped() {
        return treeEntriesSkipped;
    }

    public int getFilesSkipped() {
        return filesSkipped;
    }

    public boolean isTreeTruncated() {
        return treeTruncated;
    }

    public long getBytesDownloaded() {
        return bytesDownloaded;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }
}

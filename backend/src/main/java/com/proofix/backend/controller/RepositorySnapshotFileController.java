package com.proofix.backend.controller;

import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotFileResponse;
import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.service.RepositorySnapshotFileRetrievalService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repository/snapshots")
public class RepositorySnapshotFileController {

    private final RepositorySnapshotFileRetrievalService fileService;

    public RepositorySnapshotFileController(
            RepositorySnapshotFileRetrievalService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/{snapshotId}/files")
    public ResponseEntity<RepositorySnapshotFileResponse> getFiles(
            @PathVariable UUID snapshotId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean includeContent,
            Authentication authentication) {

        if (page < 0) {
            throw new BadRequestException("Page must not be negative");
        }
        if (size < 1 || size > 50) {
            throw new BadRequestException(
                    "Page size must be between 1 and 50");
        }

        FileCategory fileCategory = parseCategory(category);
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(fileService.getFiles(
                snapshotId,
                userId,
                PageRequest.of(page, size),
                fileCategory,
                includeContent));
    }

    private static FileCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return FileCategory.valueOf(category);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid file category");
        }
    }
}

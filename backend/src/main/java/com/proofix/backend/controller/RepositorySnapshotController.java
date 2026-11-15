package com.proofix.backend.controller;

import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotHistoryResponse.SnapshotMetadata;
import com.proofix.backend.service.RepositorySnapshotHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repository/snapshots")
public class RepositorySnapshotController {

    private final RepositorySnapshotHistoryService snapshotService;

    public RepositorySnapshotController(
            RepositorySnapshotHistoryService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/{snapshotId}")
    public ResponseEntity<SnapshotMetadata> getSnapshot(
            @PathVariable UUID snapshotId,
            Authentication authentication) {

        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(snapshotService.getSnapshot(
                snapshotId, userId));
    }
}

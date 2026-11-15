package com.proofix.backend.controller;

import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotHistoryResponse;
import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.service.RepositorySnapshotHistoryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repository/connections")
public class RepositorySnapshotHistoryController {

    private final RepositorySnapshotHistoryService historyService;

    public RepositorySnapshotHistoryController(
            RepositorySnapshotHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/{repositoryConnectionId}/snapshots")
    public ResponseEntity<RepositorySnapshotHistoryResponse> getHistory(
            @PathVariable UUID repositoryConnectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication) {

        UUID userId = UUID.fromString(authentication.getName());
        if (page < 0) {
            throw new BadRequestException("Page must not be negative");
        }
        if (size < 1 || size > 50) {
            throw new BadRequestException(
                    "Page size must be between 1 and 50");
        }
        RepositorySnapshotHistoryResponse response =
                historyService.getHistory(
                        repositoryConnectionId,
                        userId,
                        PageRequest.of(page, size));

        return ResponseEntity.ok(response);
    }

}

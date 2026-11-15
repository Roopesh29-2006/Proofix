package com.proofix.backend.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.proofix.backend.dto.RepositoryAnalysisRequest;
import com.proofix.backend.dto.RepositoryAnalysisResponse;
import com.proofix.backend.service.RepositoryAnalysisOrchestrationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/repository")
public class RepositoryAnalysisController {

    private final RepositoryAnalysisOrchestrationService
            repositoryAnalysisOrchestrationService;

    public RepositoryAnalysisController(
            RepositoryAnalysisOrchestrationService
                    repositoryAnalysisOrchestrationService) {
        this.repositoryAnalysisOrchestrationService =
                repositoryAnalysisOrchestrationService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<RepositoryAnalysisResponse> analyze(
            @Valid @RequestBody RepositoryAnalysisRequest request,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        RepositoryAnalysisOrchestrationService.AnalysisOutcome outcome =
                repositoryAnalysisOrchestrationService.analyze(
                        request.getRepositoryUrl(),
                        request.getRepositoryConnectionId(),
                        userId);

        return ResponseEntity.ok(new RepositoryAnalysisResponse(
                outcome.result(), outcome.snapshotId()));
    }
}

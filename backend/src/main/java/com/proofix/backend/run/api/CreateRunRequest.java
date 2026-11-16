package com.proofix.backend.run.api;

import jakarta.validation.constraints.NotBlank;

public record CreateRunRequest(@NotBlank(message = "Repository URL is required") String repoUrl) {
}

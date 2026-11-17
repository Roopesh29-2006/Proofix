package com.proofix.backend.run.api;

import java.util.List;

public record RunPageResponse(
        List<RunSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}

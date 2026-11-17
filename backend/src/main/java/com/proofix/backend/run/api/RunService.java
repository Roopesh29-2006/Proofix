package com.proofix.backend.run.api;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.run.Run;
import com.proofix.backend.run.RunLifecycle;
import com.proofix.backend.run.RunLimits;
import com.proofix.backend.service.RepositoryAnalysisService;

/**
 * API-facing Run operations. Deliberately NOT transactional: create does
 * the GitHub call with no transaction or connection held, and each
 * RunLifecycle call opens its own short transaction.
 */
@Service
public class RunService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final RunLifecycle lifecycle;
    private final RepositoryAnalysisService repositoryAnalysisService;
    private final RunProperties properties;

    public RunService(
            RunLifecycle lifecycle,
            RepositoryAnalysisService repositoryAnalysisService,
            RunProperties properties) {
        this.lifecycle = lifecycle;
        this.repositoryAnalysisService = repositoryAnalysisService;
        this.properties = properties;
    }

    public RunResponse create(UUID owner, String repoUrl) {
        // 1. Strict URL rule (400), no I/O.
        String url = RunRepoUrl.normalize(repoUrl);
        // 2. Cheap pre-check (short read-only transaction) so a deleted or
        //    over-cap user cannot drive GitHub calls.
        RunLimits limits = properties.limits();
        lifecycle.checkCreateLimits(owner, limits);
        // 3. Network call to GitHub with no transaction open (403/404/5xx).
        repositoryAnalysisService.verifyPublicRepository(url);
        // 4. Authoritative limit checks and insert under the owner's row lock.
        Run run = lifecycle.createWithinLimits(
                owner, url, properties.defaults().budgets(), limits);
        return RunResponse.from(run);
    }

    public RunResponse get(UUID owner, UUID runId) {
        return RunResponse.from(lifecycle.get(owner, runId));
    }

    public RunPageResponse list(UUID owner, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Page<Run> result = lifecycle.list(owner, PageRequest.of(page, size, NEWEST_FIRST));
        return new RunPageResponse(
                result.getContent().stream().map(RunSummary::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    public RunResponse cancel(UUID owner, UUID runId) {
        return RunResponse.from(lifecycle.cancel(owner, runId));
    }

    public RunResponse approve(UUID owner, UUID runId) {
        return RunResponse.from(lifecycle.approve(owner, runId, properties.limits()));
    }

    public RunResponse reject(UUID owner, UUID runId) {
        return RunResponse.from(lifecycle.reject(owner, runId));
    }
}

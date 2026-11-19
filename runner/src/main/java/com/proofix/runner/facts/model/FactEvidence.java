package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;
import java.util.Objects;

/**
 * Where a fact came from. {@code rule} names the extraction rule; {@code path} is repo-relative;
 * {@code line} is 1-based or null when not tied to a line; {@code commitSha} is null outside git;
 * {@code sha256} is the hash of the whole file as read (null if it was not read).
 */
@JsonPropertyOrder({"rule", "path", "line", "commitSha", "sha256"})
public record FactEvidence(String rule, String path, Integer line, String commitSha, String sha256) {

    public static final Comparator<FactEvidence> ORDER = Comparator
            .comparing(FactEvidence::path)
            .thenComparing(FactEvidence::line, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FactEvidence::rule);

    public FactEvidence {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(path, "path");
        if (line != null && line < 1) throw new IllegalArgumentException("line is 1-based");
    }
}

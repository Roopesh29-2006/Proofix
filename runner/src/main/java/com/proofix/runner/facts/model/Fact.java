package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A content fact extracted from a build file (e.g. the Java version). Defined for Build 2B-0b;
 * Build 2B-0a never emits one, so "facts" is always an empty array.
 */
@JsonPropertyOrder({"name", "value", "status", "label", "source", "evidence"})
public record Fact(String name, String value, String status, String label, String source,
                   List<FactEvidence> evidence) {
    public Fact {
        evidence = List.copyOf(evidence);
    }

    /** line is 1-based, or null when the fact is not tied to a line. */
    @JsonPropertyOrder({"path", "line"})
    public record FactEvidence(String path, Integer line) {}
}

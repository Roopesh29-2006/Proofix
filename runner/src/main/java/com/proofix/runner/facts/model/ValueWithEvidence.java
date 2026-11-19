package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** A value with its sources: an entry of a fact's "overridden" or "candidates" list. */
@JsonPropertyOrder({"value", "evidence"})
public record ValueWithEvidence(String value, List<FactEvidence> evidence) {
    public ValueWithEvidence {
        evidence = Fact.sortedEvidence(evidence);
    }
}

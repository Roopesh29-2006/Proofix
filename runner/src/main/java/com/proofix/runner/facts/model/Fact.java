package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/**
 * A VERIFIED content fact read from a build or config file (schemaVersion 2).
 *
 * <ul>
 *   <li>{@code module}: module directory relative to the repo root ("." for the root).</li>
 *   <li>{@code profile}: Spring profile ("default", "prod", ...) for Spring config facts; else null.</li>
 *   <li>{@code envVar}: the env var a placeholder reads; left out of the JSON when null.</li>
 *   <li>{@code overridden}: losers of a precedence rule, in precedence order (caller supplies it).</li>
 *   <li>{@code candidates}: the disagreeing values; non-empty only when status is CONFLICT.</li>
 *   <li>{@code reason}: required unless status is KNOWN; null otherwise unless one is needed.</li>
 * </ul>
 * {@code overridden} and {@code candidates} are always arrays, never null.
 */
@JsonPropertyOrder({"name", "value", "status", "label", "module", "profile", "envVar",
        "evidence", "overridden", "candidates", "reason"})
public record Fact(String name, String value, FactStatus status, String label, String module, String profile,
                   @JsonInclude(JsonInclude.Include.NON_NULL) String envVar,
                   List<FactEvidence> evidence, List<ValueWithEvidence> overridden,
                   List<ValueWithEvidence> candidates, String reason) implements FactEntry {

    public static final String VERIFIED = "VERIFIED";

    public Fact {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(module, "module");
        evidence = sortedEvidence(evidence);
        overridden = overridden == null ? List.of() : List.copyOf(overridden);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (status != FactStatus.KNOWN && reason == null) {
            throw new IllegalArgumentException(status + " fact needs a reason");
        }
        if (status == FactStatus.CONFLICT) {
            if (candidates.size() < 2) throw new IllegalArgumentException("CONFLICT needs at least two candidates");
            if (value != null) throw new IllegalArgumentException("CONFLICT has no single value");
        } else if (!candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates are only allowed when status is CONFLICT");
        }
    }

    static List<FactEvidence> sortedEvidence(List<FactEvidence> evidence) {
        return evidence == null ? List.of() : evidence.stream().sorted(FactEvidence.ORDER).toList();
    }
}

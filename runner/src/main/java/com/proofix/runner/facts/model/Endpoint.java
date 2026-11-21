package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An HTTP endpoint found in Java source (schemaVersion 3). {@code path} is null when it could not
 * be evaluated; {@code rawPath} (the expression text, at most 200 chars) is set only then.
 * {@code reason} is required when UNRESOLVED and may explain a KNOWN path ("context path unknown").
 */
@JsonPropertyOrder({"method", "path", "pathVariables", "handler", "module", "status", "reason", "rawPath", "evidence"})
public record Endpoint(String method, String path, List<String> pathVariables, String handler, String module,
                       FactStatus status, String reason, String rawPath, List<FactEvidence> evidence) {

    public static final int MAX_RAW_PATH = 200;

    public static final Comparator<Endpoint> ORDER = Comparator
            .comparing(Endpoint::path, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Endpoint::method)
            .thenComparing(Endpoint::handler)
            .thenComparing(Endpoint::rawPath, Comparator.nullsFirst(Comparator.naturalOrder()));

    public Endpoint {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(status, "status");
        if (status != FactStatus.KNOWN && status != FactStatus.UNRESOLVED) {
            throw new IllegalArgumentException("endpoint status is KNOWN or UNRESOLVED");
        }
        if (status == FactStatus.UNRESOLVED && reason == null) throw new IllegalArgumentException("UNRESOLVED needs a reason");
        if (path == null && status == FactStatus.KNOWN) throw new IllegalArgumentException("KNOWN endpoint needs a path");
        if (rawPath != null && (path != null || rawPath.length() > MAX_RAW_PATH)) {
            throw new IllegalArgumentException("rawPath only for an unknown path, at most " + MAX_RAW_PATH + " chars");
        }
        pathVariables = pathVariables == null ? List.of() : List.copyOf(pathVariables);
        evidence = Fact.sortedEvidence(evidence);
    }
}

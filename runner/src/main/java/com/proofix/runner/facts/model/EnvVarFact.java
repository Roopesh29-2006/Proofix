package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

/**
 * An environment variable the app reads through an explicit ${NAME} placeholder. {@code required}
 * is true when at least one use has no default. {@code defaultValue} is null when there is no
 * default or when the key or the variable name looks secret (a secret default is never emitted).
 * {@code reason} (schemaVersion 3) is set when the variable is only read through System.getenv.
 */
@JsonPropertyOrder({"name", "required", "default", "evidence", "reason"})
public record EnvVarFact(String name, boolean required,
                         @JsonProperty("default") String defaultValue,
                         List<FactEvidence> evidence, String reason) implements FactEntry {

    public static final String PREFIX = "env.";

    public EnvVarFact(String name, boolean required, String defaultValue, List<FactEvidence> evidence) {
        this(name, required, defaultValue, evidence, null);
    }

    public EnvVarFact {
        Objects.requireNonNull(name, "name");
        if (!name.startsWith(PREFIX) || name.length() == PREFIX.length()) {
            throw new IllegalArgumentException("env var fact name must be env.<NAME>");
        }
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("a required env var has no default");
        }
        evidence = Fact.sortedEvidence(evidence);
    }
}

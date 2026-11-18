package com.proofix.runner.scan.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;

/**
 * A non-fatal finding. {@code path} is relative (or null); {@code message} is always a fixed,
 * code-generated string and never contains file contents or process output.
 */
@JsonPropertyOrder({"code", "path", "message"})
public record Warning(String code, String path, String message) {

    public static final Comparator<Warning> ORDER = Comparator
            .comparing(Warning::code)
            .thenComparing(Warning::path, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Warning::message);
}

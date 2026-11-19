package com.proofix.runner.scan.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;

/**
 * A non-fatal finding. {@code path} is relative (or null); {@code message} is always a fixed,
 * code-generated string and never contains file contents or process output. {@code key} (a config
 * key NAME, never its value) and {@code line} (1-based) are optional and left out of the JSON when
 * null, so warnings that do not use them serialize exactly as in schemaVersion 1.
 */
@JsonPropertyOrder({"code", "key", "path", "line", "message"})
public record Warning(String code,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String key,
                      String path,
                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer line,
                      String message) {

    public Warning(String code, String path, String message) {
        this(code, null, path, null, message);
    }

    public static final Comparator<Warning> ORDER = Comparator
            .comparing(Warning::code)
            .thenComparing(Warning::path, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Warning::line, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Warning::key, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Warning::message);
}

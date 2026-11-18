package com.proofix.runner.detect.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** File-presence evidence for a stack. No line numbers: these are not content facts. */
@JsonPropertyOrder({"rule", "confirmed", "confirmReason", "matched"})
public record Evidence(String rule, boolean confirmed, String confirmReason, List<MatchedFile> matched) {
    public Evidence {
        matched = List.copyOf(matched);
    }
}

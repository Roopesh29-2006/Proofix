package com.proofix.runner.detect.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A marker file that matched a rule. sha256 is null when the file exceeded the size limit. */
@JsonPropertyOrder({"path", "sha256", "size"})
public record MatchedFile(String path, String sha256, long size) {}

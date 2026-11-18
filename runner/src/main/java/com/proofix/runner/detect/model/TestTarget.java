package com.proofix.runner.detect.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** status: KNOWN | UNRESOLVED. reason: one of the REASON_* codes. */
@JsonPropertyOrder({"stackId", "status", "reason"})
public record TestTarget(String stackId, String status, String reason) {
    public static final String KNOWN = "KNOWN";
    public static final String UNRESOLVED = "UNRESOLVED";

    public static final String SINGLE_BACKEND = "SINGLE_BACKEND";
    public static final String ONLY_STACK = "ONLY_STACK";
    public static final String MULTIPLE_BACKENDS = "MULTIPLE_BACKENDS";
    public static final String MULTIPLE_FRONTENDS = "MULTIPLE_FRONTENDS";
    public static final String NO_TESTABLE_STACK = "NO_TESTABLE_STACK";
    public static final String NO_STACK_DETECTED = "NO_STACK_DETECTED";
}

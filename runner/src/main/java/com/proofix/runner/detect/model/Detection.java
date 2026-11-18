package com.proofix.runner.detect.model;

import com.proofix.runner.scan.model.Warning;

import java.util.List;

/** Output of the stack detector. */
public record Detection(List<Stack> stacks, TestTarget testTarget, List<Warning> warnings) {
    public Detection {
        stacks = List.copyOf(stacks);
        warnings = List.copyOf(warnings);
    }
}

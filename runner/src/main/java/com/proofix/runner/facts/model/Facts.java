package com.proofix.runner.facts.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.proofix.runner.detect.model.Stack;
import com.proofix.runner.detect.model.TestTarget;
import com.proofix.runner.scan.model.Warning;

import java.util.List;

/** Root of facts.json (schemaVersion 2). Deterministic: no timestamps, no absolute paths. */
@JsonPropertyOrder({"schemaVersion", "repo", "scan", "stacks", "testTarget", "facts", "warnings"})
public record Facts(int schemaVersion, Repo repo, Scan scan, List<Stack> stacks, TestTarget testTarget,
                    List<FactEntry> facts, List<Warning> warnings) {

    public static final int SCHEMA_VERSION = 2;

    public Facts {
        stacks = List.copyOf(stacks);
        facts = facts.stream().sorted(FactEntry.ORDER).toList();
        warnings = List.copyOf(warnings);
    }

    @JsonPropertyOrder({"commitSha", "dirty", "fileSource"})
    public record Repo(String commitSha, boolean dirty, String fileSource) {}

    @JsonPropertyOrder({"filesSeen", "dirsSkipped", "limitsHit"})
    public record Scan(int filesSeen, List<String> dirsSkipped, List<String> limitsHit) {
        public Scan {
            dirsSkipped = List.copyOf(dirsSkipped);
            limitsHit = List.copyOf(limitsHit);
        }
    }
}

package com.proofix.runner.detect.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A detected stack. id is "kind@path" ("." for the repository root); parent is the id of the
 * nearest enclosing stack of the same ecosystem, or null.
 */
@JsonPropertyOrder({"id", "kind", "path", "role", "parent", "members", "evidence"})
public record Stack(String id, String kind, String path, String role, String parent,
                    List<String> members, Evidence evidence) {
    public Stack {
        members = List.copyOf(members);
    }

    public static String id(String kind, String path) {
        return kind + "@" + path;
    }
}

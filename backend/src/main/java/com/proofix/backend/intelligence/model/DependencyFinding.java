package com.proofix.backend.intelligence.model;

public record DependencyFinding(
        String ecosystem,
        String groupId,
        String artifactId,
        String version,
        VersionStatus versionStatus,
        String scope,
        Boolean optional,
        String classifier,
        String type,
        EvidenceReference evidence) {

    public enum VersionStatus {
        EXPLICIT,
        PROPERTY_REFERENCE,
        MISSING,
        UNRESOLVED
    }
}

package com.proofix.backend.intelligence.model;

import java.util.List;
import java.util.Map;

public record DependencyManifestParseResult(
        String filePath,
        ParseStatus status,
        List<DependencyFinding> dependencies,
        Map<String, String> properties,
        Map<String, String> javaVersionCandidates,
        String selectedJavaVersion,
        MavenArtifact springBootParent,
        MavenArtifact springBootPlugin,
        List<String> warnings) {

    public DependencyManifestParseResult {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        javaVersionCandidates = javaVersionCandidates == null
                ? Map.of()
                : Map.copyOf(javaVersionCandidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record MavenArtifact(
            String groupId,
            String artifactId,
            String version,
            DependencyFinding.VersionStatus versionStatus) {
    }
}

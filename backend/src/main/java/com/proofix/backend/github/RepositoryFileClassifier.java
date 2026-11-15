package com.proofix.backend.github;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class RepositoryFileClassifier {

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".c", ".cpp", ".cs", ".go", ".java", ".js", ".jsx",
            ".kt", ".php", ".py", ".rb", ".rs", ".scala", ".swift",
            ".ts", ".tsx");

    private static final Set<String> MANIFEST_NAMES = Set.of(
            "build.gradle", "build.gradle.kts", "cargo.toml", "composer.json",
            "go.mod", "package.json", "pom.xml", "pyproject.toml",
            "requirements.txt", "setup.py");

    private static final Set<String> DEPLOYMENT_NAMES = Set.of(
            "dockerfile", "docker-compose.yml", "docker-compose.yaml");

    public FileCategory classify(String path) {
        if (path == null || path.isBlank()) {
            return FileCategory.UNKNOWN;
        }

        String normalizedPath = path.replace('\\', '/');
        String fileName = normalizedPath.substring(
                normalizedPath.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);

        if (fileName.startsWith(".env")) {
            return FileCategory.CONFIGURATION;
        }

        if (MANIFEST_NAMES.contains(fileName)) {
            return FileCategory.DEPENDENCY_MANIFEST;
        }

        if (DEPLOYMENT_NAMES.contains(fileName)
                || normalizedPath.startsWith(".github/workflows/")) {
            return FileCategory.DEPLOYMENT;
        }

        if (fileName.endsWith(".yml")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".json")
                || fileName.endsWith(".xml")) {
            return FileCategory.CONFIGURATION;
        }

        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
        for (String extension : SOURCE_EXTENSIONS) {
            if (lowerPath.endsWith(extension)) {
                return FileCategory.SOURCE;
            }
        }

        return FileCategory.OTHER;
    }

    public enum FileCategory {
        SOURCE,
        DEPENDENCY_MANIFEST,
        CONFIGURATION,
        DEPLOYMENT,
        OTHER,
        UNKNOWN
    }
}

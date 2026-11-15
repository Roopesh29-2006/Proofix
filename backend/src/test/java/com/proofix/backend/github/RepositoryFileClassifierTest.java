package com.proofix.backend.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RepositoryFileClassifierTest {

    private final RepositoryFileClassifier classifier =
            new RepositoryFileClassifier();

    @Test
    void classifiesSourceAndDependencyFiles() {
        assertEquals(
                RepositoryFileClassifier.FileCategory.SOURCE,
                classifier.classify("src/main/App.java"));
        assertEquals(
                RepositoryFileClassifier.FileCategory.DEPENDENCY_MANIFEST,
                classifier.classify("pom.xml"));
    }

    @Test
    void classifiesConfigurationAndDeploymentFiles() {
        assertEquals(
                RepositoryFileClassifier.FileCategory.CONFIGURATION,
                classifier.classify("application.yml"));
        assertEquals(
                RepositoryFileClassifier.FileCategory.DEPLOYMENT,
                classifier.classify(".github/workflows/build.yml"));
        assertEquals(
                RepositoryFileClassifier.FileCategory.DEPLOYMENT,
                classifier.classify("Dockerfile"));
    }
}

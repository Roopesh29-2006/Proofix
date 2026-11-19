package com.proofix.runner.detect;

import com.proofix.runner.scan.model.ScannedFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule glob (one '*' anywhere, one or more chars) and the content-file allowlist. */
class RuleTest {

    @Test
    void suffixGlobKeepsItsMeaning() {
        assertTrue(Rule.matches("*.csproj", "Api.csproj"));
        assertFalse(Rule.matches("*.csproj", ".csproj"));
        assertFalse(Rule.matches("*.csproj", "Api.csproj.bak"));
        assertTrue(Rule.matches("pom.xml", "pom.xml"));
        assertFalse(Rule.matches("pom.xml", "xpom.xml"));
    }

    @Test
    void starInTheMiddleNeedsAtLeastOneChar() {
        assertTrue(Rule.matches("application-*.yml", "application-prod.yml"));
        assertFalse(Rule.matches("application-*.yml", "application-.yml"));
        assertFalse(Rule.matches("application-*.yml", "application.yml"));
        assertFalse(Rule.matches("application-*.yml", "application-prod.yaml"));
    }

    @Test
    void moreThanOneStarIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Rule.matches("*-*.yml", "a-b.yml"));
    }

    @Test
    void springConfigOnlyUnderSrcMainResources() {
        assertTrue(RuleTable.isContentFile(new ScannedFile("src/main/resources/application.yml", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile("api/src/main/resources/application.yaml", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile("api/src/main/resources/application.properties", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile("api/src/main/resources/application-prod.yml", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile("src/main/resources/application-dev.properties", 1)));

        assertFalse(RuleTable.isContentFile(new ScannedFile("application.yml", 1)));
        assertFalse(RuleTable.isContentFile(new ScannedFile("src/test/resources/application.yml", 1)));
        assertFalse(RuleTable.isContentFile(new ScannedFile("src/main/resources/config/application.yml", 1)));
        assertFalse(RuleTable.isContentFile(new ScannedFile("xsrc/main/resources/application.yml", 1)));
        assertFalse(RuleTable.isContentFile(new ScannedFile("src/main/resources/bootstrap.yml", 1)));
    }

    @Test
    void dockerfileAndJavaVersionAnywhere() {
        assertTrue(RuleTable.isContentFile(new ScannedFile("Dockerfile", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile("api/Dockerfile", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile(".java-version", 1)));
        assertTrue(RuleTable.isContentFile(new ScannedFile("api/.java-version", 1)));
        assertFalse(RuleTable.isContentFile(new ScannedFile("Dockerfile.dev", 1)));
        assertFalse(RuleTable.isContentFile(new ScannedFile("README.md", 1)));
    }
}

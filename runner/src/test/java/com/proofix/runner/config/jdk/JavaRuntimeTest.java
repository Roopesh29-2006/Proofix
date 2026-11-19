package com.proofix.runner.config.jdk;

import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;
import com.proofix.runner.testutil.ConfigHarness;
import com.proofix.runner.testutil.ConfigHarness.Run;
import com.proofix.runner.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.proofix.runner.testutil.ConfigHarness.cites;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dockerfile / .java-version runtime JDK vs the compile level. */
class JavaRuntimeTest {

    @TempDir
    Path tmp;

    private static String major(String dockerfile) {
        DockerfileJdk.Runtime r = DockerfileJdk.parse(dockerfile.getBytes(StandardCharsets.UTF_8));
        return r == null ? null : r.major();
    }

    @Test
    void imageTags() {
        assertEquals("17", major("FROM eclipse-temurin:17-jre-alpine\n"));
        assertEquals("8", major("FROM openjdk:8u392\n"));
        assertEquals("11", major("FROM openjdk:11.0.2\n"));
        assertEquals("8", major("FROM openjdk:1.8\n"));
        assertEquals("21", major("FROM amazoncorretto:21-alpine\n"));
        assertEquals("17", major("FROM public.ecr.aws/amazoncorretto/amazoncorretto:17\n"));
        assertEquals("21", major("FROM --platform=linux/amd64 docker.io/library/eclipse-temurin:21-jre@sha256:abc\n"));
        assertNull(major("FROM eclipse-temurin:latest\n"));
        assertNull(major("FROM eclipse-temurin\n"));
        assertNull(major("FROM node:20\n"));
        assertNull(major("FROM ${BASE}\n"));
        assertNull(major("FROM eclipse-temurin:99999999999\n"), "absurd versions are not numbers we parse");
        assertNull(major("# FROM eclipse-temurin:17\n"));
    }

    @Test
    void finalStageAndAliases() {
        assertEquals("17", major("FROM maven:3-eclipse-temurin-21 AS build\nRUN mvn package\nFROM eclipse-temurin:17-jre\n"));
        assertEquals("21", major("FROM eclipse-temurin:21-jdk AS base\nFROM base AS build\nFROM build\n"));
        DockerfileJdk.Runtime r = DockerfileJdk.parse("FROM \\\n  eclipse-temurin:21\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("21", r.major());
        assertEquals(1, r.line());
    }

    @Test
    void javaVersionFile() {
        assertEquals("17", JavaVersionFile.parse("17\n".getBytes(StandardCharsets.UTF_8)).major());
        assertEquals("21", JavaVersionFile.parse("# c\ntemurin-21.0.1\n".getBytes(StandardCharsets.UTF_8)).major());
        assertEquals("8", JavaVersionFile.parse("1.8\n".getBytes(StandardCharsets.UTF_8)).major());
        assertNull(JavaVersionFile.parse("system\n".getBytes(StandardCharsets.UTF_8)));
    }

    // --- against the compile level ---------------------------------------------------------

    private Path app(String javaProps, String... files) {
        Path r = tmp.resolve("repo-" + UUID.randomUUID());
        Fixtures.write(r, "pom.xml", "<project>\n<groupId>g</groupId><artifactId>app</artifactId>\n" + javaProps
                + "\n<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>\n</project>\n");
        for (int i = 0; i < files.length; i += 2) Fixtures.write(r, files[i], files[i + 1]);
        return r;
    }

    private static final String RELEASE_21 = "<properties><maven.compiler.release>21</maven.compiler.release></properties>";
    private static final String RELEASE_17 = "<properties><maven.compiler.release>17</maven.compiler.release></properties>";

    @Test
    void runtime17OnCompile21IsConflict() {
        Run r = ConfigHarness.run(app(RELEASE_21, "Dockerfile", "FROM eclipse-temurin:17-jre\n"));
        Fact f = r.one(".", "java.version");
        assertEquals(FactStatus.CONFLICT, f.status());
        assertNull(f.value());
        assertEquals(JavaVersionConflict.OLDER_REASON, f.reason());
        assertEquals(List.of("21", "17"), f.candidates().stream().map(ValueWithEvidence::value).toList());
        assertTrue(cites(f, JavaVersionConflict.RULE_OLDER));
        assertTrue(cites(f, "Dockerfile", 1));
    }

    @Test
    void runtime21OnCompile17IsCompatible() {
        Run r = ConfigHarness.run(app(RELEASE_17, "Dockerfile", "FROM eclipse-temurin:21-jre\n"));
        Fact f = r.one(".", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals("17", f.value());
        assertTrue(cites(f, JavaVersionConflict.RULE_COMPATIBLE));
        assertTrue(f.candidates().isEmpty());
    }

    @Test
    void javaVersionFileOlderIsConflict() {
        Run r = ConfigHarness.run(app(RELEASE_21, ".java-version", "17\n"));
        assertEquals(FactStatus.CONFLICT, r.one(".", "java.version").status());
    }

    @Test
    void sameRuntimeIsCompatible() {
        Run r = ConfigHarness.run(app(RELEASE_17, "Dockerfile", "FROM eclipse-temurin:17\n", ".java-version", "17\n"));
        Fact f = r.one(".", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals(2, f.evidence().stream().filter(e -> e.rule().equals(JavaVersionConflict.RULE_COMPATIBLE)).count());
    }

    @Test
    void unresolvedStaysUnresolvedAndRuntimeIsOnlyCited() {
        Run r = ConfigHarness.run(app("", "Dockerfile", "FROM eclipse-temurin:17\n"));
        Fact f = r.one(".", "java.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertNull(f.value(), "a runtime image never fills an UNRESOLVED compile level");
        assertTrue(cites(f, JavaVersionConflict.RULE_UNVERIFIED));
    }

    @Test
    void defaultedBootLevelIsCheckedToo() {
        Path r0 = tmp.resolve("boot");
        Fixtures.write(r0, "pom.xml", "<project>\n<parent><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-parent</artifactId><version>3.5.0</version><relativePath/></parent>\n"
                + "<artifactId>app</artifactId>\n<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>\n</project>\n");
        Fixtures.write(r0, "Dockerfile", "FROM eclipse-temurin:11\n");
        Fact f = ConfigHarness.run(r0).one(".", "java.version");
        assertEquals(FactStatus.CONFLICT, f.status());
        assertEquals("17", f.candidates().get(0).value());
    }

    @Test
    void nearestDockerfileWins() {
        Path r0 = tmp.resolve("multi");
        Fixtures.write(r0, "pom.xml", "<project><groupId>g</groupId><artifactId>p</artifactId><version>1</version>"
                + "<packaging>pom</packaging><modules><module>api</module></modules></project>");
        Fixtures.write(r0, "api/pom.xml", "<project><parent><groupId>g</groupId><artifactId>p</artifactId>"
                + "<version>1</version></parent><artifactId>api</artifactId>" + RELEASE_21
                + "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>");
        Fixtures.write(r0, "Dockerfile", "FROM eclipse-temurin:17\n");
        Fixtures.write(r0, "api/Dockerfile", "FROM eclipse-temurin:21\n");
        Fact f = ConfigHarness.run(r0).one("api", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertTrue(cites(f, "api/Dockerfile", 1));
        assertFalse(f.evidence().stream().anyMatch(e -> e.path().equals("Dockerfile")));
    }

    @Test
    void nonAppModuleIsNotChecked() {
        Path r0 = tmp.resolve("lib");
        Fixtures.write(r0, "pom.xml", "<project><groupId>g</groupId><artifactId>lib</artifactId>" + RELEASE_21 + "</project>");
        Fixtures.write(r0, "Dockerfile", "FROM eclipse-temurin:17\n");
        assertEquals(FactStatus.KNOWN, ConfigHarness.run(r0).one(".", "java.version").status());
    }

    @Test
    void unrecognisedDockerfileAddsNothing() {
        Run r = ConfigHarness.run(app(RELEASE_21, "Dockerfile", "ARG BASE=eclipse-temurin:17\nFROM ${BASE}\n"));
        Fact f = r.one(".", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertFalse(f.evidence().stream().anyMatch(e -> e.path().equals("Dockerfile")));
    }
}

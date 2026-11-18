package com.proofix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackFixturesTest {

    @TempDir
    Path tmp;

    private Outcome run(String fixture) {
        Outcome o = RunnerHarness.scan(Fixtures.load(fixture, tmp), tmp.resolve("out"));
        assertEquals(0, o.exit(), o.stderr());
        return o;
    }

    private static void assertTarget(Outcome o, String stackId, String status, String reason) {
        JsonNode t = o.json().get("testTarget");
        if (stackId == null) assertTrue(t.get("stackId").isNull());
        else assertEquals(stackId, t.get("stackId").asText());
        assertEquals(status, t.get("status").asText());
        assertEquals(reason, t.get("reason").asText());
    }

    @Test
    void expressApiIsBackend() {
        Outcome o = run("express-api");
        assertEquals(List.of("node@."), o.stackIds());
        assertEquals("backend", o.stack("node@.").get("role").asText());
        assertTarget(o, "node@.", "KNOWN", "SINGLE_BACKEND");
    }

    @Test
    void viteSpaIsFrontendAndOnlyStack() {
        Outcome o = run("vite-spa");
        assertEquals("frontend", o.stack("node@.").get("role").asText());
        assertTarget(o, "node@.", "KNOWN", "ONLY_STACK");
    }

    @Test
    void pythonScriptOnlyIsTooling() {
        Outcome o = run("python-script-only");
        JsonNode s = o.stack("python@.");
        assertEquals("tooling", s.get("role").asText());
        assertEquals(false, s.get("evidence").get("confirmed").asBoolean());
        assertTarget(o, null, "UNRESOLVED", "NO_TESTABLE_STACK");
    }

    @Test
    void djangoIsBackend() {
        Outcome o = run("django");
        JsonNode s = o.stack("python@.");
        assertEquals("backend", s.get("role").asText());
        assertEquals("entry file manage.py", s.get("evidence").get("confirmReason").asText());
        assertTarget(o, "python@.", "KNOWN", "SINGLE_BACKEND");
    }

    @Test
    void docsRequirementsIsAuxiliary() {
        Outcome o = run("docs-aux");
        assertEquals(List.of("python@docs"), o.stackIds());
        assertEquals("auxiliary", o.stack("python@docs").get("role").asText());
        assertTarget(o, null, "UNRESOLVED", "NO_TESTABLE_STACK");
    }

    @Test
    void twoBackendsAreUnresolved() {
        Outcome o = run("two-backends");
        assertEquals(List.of("go@svc-go", "python@svc-py"), o.stackIds());
        assertEquals("backend", o.stack("go@svc-go").get("role").asText());
        assertEquals("backend", o.stack("python@svc-py").get("role").asText());
        assertTarget(o, null, "UNRESOLVED", "MULTIPLE_BACKENDS");
    }

    @Test
    void emptyDirHasNoStack() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Outcome o = RunnerHarness.scan(empty, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertEquals(0, o.json().get("stacks").size());
        assertEquals(0, o.json().get("scan").get("filesSeen").asInt());
        assertTarget(o, null, "UNRESOLVED", "NO_STACK_DETECTED");
    }

    @Test
    void pomAndPackageJsonInSameDirAreBothKept() {
        Outcome o = run("pom-and-package");
        assertEquals(List.of("java-maven@.", "node@."), o.stackIds());
        assertEquals("backend", o.stack("java-maven@.").get("role").asText());
        assertEquals("tooling", o.stack("node@.").get("role").asText());
        assertTrue(o.stack("node@.").get("parent").isNull());
        assertTarget(o, "java-maven@.", "KNOWN", "SINGLE_BACKEND");
    }

    @Test
    void dockerfileOnlyWarns() {
        Path repo = tmp.resolve("docker");
        Fixtures.write(repo, "Dockerfile", "FROM scratch\n");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertTrue(o.hasWarning("DOCKERFILE_ONLY", "Dockerfile"));
        assertTarget(o, null, "UNRESOLVED", "NO_STACK_DETECTED");
    }
}

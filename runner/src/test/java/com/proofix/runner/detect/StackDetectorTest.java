package com.proofix.runner.detect;

import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.detect.model.Stack;
import com.proofix.runner.scan.Limits;
import com.proofix.runner.scan.model.GitInfo;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule table, folding, confirmation and role logic on hand-built repositories. */
class StackDetectorTest {

    @TempDir
    Path root;

    private Detection detect(Map<String, String> files) throws IOException {
        return detect(files, Limits.DEFAULT_MAX_MARKER_BYTES);
    }

    private Detection detect(Map<String, String> files, long maxMarker) throws IOException {
        List<ScannedFile> list = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
            Fixtures.write(root, e.getKey(), e.getValue());
            list.add(new ScannedFile(e.getKey(), Files.size(root.resolve(e.getKey()))));
        }
        ScanResult scan = new ScanResult(root, list, List.of(), GitInfo.NONE, "walk", List.of());
        return new StackDetector(maxMarker).detect(scan);
    }

    private static Stack stack(Detection d, String id) {
        return d.stacks().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void mostSpecificRulePerEcosystemWins() throws IOException {
        Detection d = detect(Map.of("web/package.json", "{}", "web/next.config.js", ""));
        assertEquals(List.of("nextjs@web"), d.stacks().stream().map(Stack::id).toList());
    }

    @Test
    void sameKindFoldsIntoAncestor() throws IOException {
        Detection d = detect(Map.of("settings.gradle", "", "app/build.gradle.kts", "", "lib/build.gradle", ""));
        assertEquals(1, d.stacks().size());
        Stack s = d.stacks().get(0);
        assertEquals("java-gradle@.", s.id());
        assertEquals(List.of("app", "lib"), s.members());
    }

    @Test
    void differentKindKeepsSeparateStackWithSameEcosystemParent() throws IOException {
        Detection d = detect(Map.of(
                "pom.xml", "<project/>",
                "ui/package.json", "{\"dependencies\":{\"vite\":\"5\"}}",
                "ui/admin/package.json", "{}", "ui/admin/next.config.ts", ""));
        assertNull(stack(d, "node@ui").parent(), "maven root is another ecosystem, so no parent");
        assertEquals("node@ui", stack(d, "nextjs@ui/admin").parent());
    }

    @Test
    void foldStopsAtDifferentKindBoundary() throws IOException {
        Detection d = detect(Map.of(
                "package.json", "{}",
                "web/package.json", "{}", "web/next.config.js", "",
                "web/tools/package.json", "{}"));
        assertEquals(List.of(), stack(d, "node@.").members());
        Stack nested = stack(d, "node@web/tools");
        assertEquals("nextjs@web", nested.parent());
    }

    @Test
    void dotnetGlobMarkers() throws IOException {
        Detection d = detect(Map.of("src/Api/Api.csproj", "<Project/>"));
        assertEquals("dotnet@src/Api", d.stacks().get(0).id());
        assertEquals("backend", d.stacks().get(0).role());
    }

    @Test
    void nodeServerDepWithoutEntryIsTooling() throws IOException {
        Detection d = detect(Map.of("package.json", "{\"dependencies\":{\"fastify\":\"4\"}}"));
        assertEquals("tooling", d.stacks().get(0).role());
    }

    @Test
    void nodeMainPlusServerDepIsBackend() throws IOException {
        Detection d = detect(Map.of("package.json", "{\"main\":\"i.js\",\"dependencies\":{\"@nestjs/core\":\"10\"}}"));
        assertEquals("backend", d.stacks().get(0).role());
        assertEquals("server dependency @nestjs/core with main", d.stacks().get(0).evidence().confirmReason());
    }

    @Test
    void serverDepBeatsFrontendDep() throws IOException {
        Detection d = detect(Map.of("package.json",
                "{\"scripts\":{\"dev\":\"x\"},\"dependencies\":{\"express\":\"4\",\"vue\":\"3\"}}"));
        assertEquals("backend", d.stacks().get(0).role());
    }

    @Test
    void unparseablePackageJsonIsUnconfirmedTooling() throws IOException {
        Detection d = detect(Map.of("package.json", "{ not json"));
        Stack s = d.stacks().get(0);
        assertEquals("tooling", s.role());
        assertFalse(s.evidence().confirmed());
        assertEquals("MARKER_UNPARSEABLE", s.evidence().confirmReason());
        assertTrue(d.warnings().stream().anyMatch(w -> w.code().equals("MARKER_UNPARSEABLE")
                && w.path().equals("package.json")));
    }

    @Test
    void unparseableNextPackageJsonOverridesAlwaysConfirmed() throws IOException {
        Detection d = detect(Map.of("package.json", "[1,", "next.config.mjs", ""));
        assertFalse(d.stacks().get(0).evidence().confirmed());
        assertEquals("tooling", d.stacks().get(0).role());
    }

    @Test
    void pythonFrameworkInPyproject() throws IOException {
        Detection d = detect(Map.of("pyproject.toml", "[project]\ndependencies = [\"FastAPI>=0.110\"]\n"));
        assertEquals("backend", d.stacks().get(0).role());
        assertEquals("web framework fastapi in pyproject.toml", d.stacks().get(0).evidence().confirmReason());
    }

    @Test
    void pythonFrameworkTokenMatchingIgnoresCommentsAndPrefixes() {
        assertNull(PythonConfirmer.findFramework("# flask\nflask-cors==4\nmy-django-tool==1\n"));
        assertEquals("flask", PythonConfirmer.findFramework("Flask[async]>=3\n"));
        assertEquals("aiohttp", PythonConfirmer.findFramework("aiohttp = \"^3.9\"\n"));
    }

    @Test
    void auxiliaryOverridesBackend() throws IOException {
        Detection d = detect(Map.of("examples/demo/go.mod", "module x\n"));
        assertEquals("auxiliary", d.stacks().get(0).role());
        assertEquals("NO_TESTABLE_STACK", d.testTarget().reason());
    }

    @Test
    void tooLargeMarkerHasNullHashAndWarning() throws IOException {
        Detection d = detect(Map.of("package.json", "{\"dependencies\":{\"express\":\"4\"}}"), 5);
        Stack s = d.stacks().get(0);
        assertNull(s.evidence().matched().get(0).sha256());
        assertEquals("MARKER_TOO_LARGE", s.evidence().confirmReason());
        assertTrue(d.warnings().stream().anyMatch(w -> w.code().equals("FILE_TOO_LARGE")));
    }

    @Test
    void multipleFrontendsUnresolved() throws IOException {
        Detection d = detect(Map.of(
                "a/package.json", "{\"devDependencies\":{\"vite\":\"5\"}}",
                "b/package.json", "{\"dependencies\":{\"@angular/core\":\"17\"}}"));
        assertEquals("MULTIPLE_FRONTENDS", d.testTarget().reason());
        assertNull(d.testTarget().stackId());
    }

    @Test
    void markerReaderRefusesSecretAndNonMarkerFiles() {
        MarkerReader r = new MarkerReader(root, 1000);
        assertThrows(IllegalArgumentException.class, () -> r.read(new ScannedFile(".env", 1)));
        assertThrows(IllegalArgumentException.class, () -> r.read(new ScannedFile("api/.env.local", 1)));
        assertThrows(IllegalArgumentException.class, () -> r.read(new ScannedFile("src/App.java", 1)));
    }
}

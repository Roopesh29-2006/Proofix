package com.proofix.runner.source;

import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;
import com.proofix.runner.testutil.ConfigHarness;
import com.proofix.runner.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.proofix.runner.testutil.ConfigHarness.cites;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** entry.mainClass from @SpringBootApplication classes, merged with the POM plugin mainClass. */
class EntryPointsTest {

    @TempDir
    Path tmp;

    private static final String J = "src/main/java/com/q/";
    private static final String APP = "package com.q;\n@SpringBootApplication\npublic class %s {\n"
            + "  public static void main(String[] args) {}\n}\n";

    private static String pom(String mainClass) {
        return "<project>\n<groupId>com.q</groupId><artifactId>app</artifactId><version>1</version>\n"
                + "<build><plugins><plugin><groupId>org.springframework.boot</groupId>\n"
                + "<artifactId>spring-boot-maven-plugin</artifactId>\n"
                + (mainClass != null ? "<configuration><mainClass>" + mainClass + "</mainClass></configuration>\n" : "")
                + "</plugin></plugins></build>\n</project>\n";
    }

    private Fact entry(String mainClass, String... files) {
        Path r = tmp.resolve("repo-" + UUID.randomUUID());
        Fixtures.write(r, "pom.xml", pom(mainClass));
        for (int i = 0; i < files.length; i += 2) Fixtures.write(r, files[i], files[i + 1]);
        return ConfigHarness.run(r).one(".", EntryPoints.NAME);
    }

    @Test
    void oneAnnotatedClassIsKnown() {
        Fact f = entry(null, J + "App.java", APP.formatted("App"));
        assertEquals("com.q.App", f.value());
        assertEquals(FactStatus.KNOWN, f.status());
        assertTrue(cites(f.evidence(), EntryPoints.RULE, J + "App.java", 2));
    }

    @Test
    void noneIsUnresolvedAndTestSourcesAreIgnored() {
        Fact f = entry(null,
                "src/test/java/com/q/TestApp.java", APP.formatted("TestApp"),
                J + "NoMain.java", "package com.q;\n@SpringBootApplication class NoMain {}\n");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertNull(f.value());
        assertEquals("no @SpringBootApplication class with main() found", f.reason());
    }

    @Test
    void twoAreAConflict() {
        Fact f = entry(null, J + "A.java", APP.formatted("A"), J + "B.java", APP.formatted("B"));
        assertEquals(FactStatus.CONFLICT, f.status());
        assertEquals("multiple @SpringBootApplication classes", f.reason());
        assertEquals(List.of("com.q.A", "com.q.B"), f.candidates().stream().map(ValueWithEvidence::value).toList());
        assertTrue(cites(f.candidates().get(1).evidence(), EntryPoints.RULE, J + "B.java", 2));
    }

    @Test
    void pomSameClassMergesEvidence() {
        Fact f = entry("com.q.App", J + "App.java", APP.formatted("App"));
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals("com.q.App", f.value());
        assertTrue(cites(f, "boot-plugin-main-class"));
        assertTrue(cites(f, EntryPoints.RULE));
    }

    @Test
    void pomDifferentClassIsAConflict() {
        Fact f = entry("com.q.Other", J + "App.java", APP.formatted("App"));
        assertEquals(FactStatus.CONFLICT, f.status());
        assertEquals(List.of("com.q.App", "com.q.Other"), f.candidates().stream().map(ValueWithEvidence::value).toList());
        assertTrue(cites(f.candidates().get(1).evidence(), "boot-plugin-main-class", "pom.xml", 5));
    }

    @Test
    void pomOnlyStaysKnownAndUnresolvedPomStaysUnresolved() {
        assertEquals(FactStatus.KNOWN, entry("com.q.Plain", J + "Plain.java", "package com.q; class Plain {}").status());
        Fact f = entry("${start.class}", J + "App.java", APP.formatted("App"));
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertTrue(cites(f, EntryPoints.RULE));
    }
}

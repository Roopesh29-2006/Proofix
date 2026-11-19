package com.proofix.runner.config.maven;

import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.testutil.ConfigHarness;
import com.proofix.runner.testutil.ConfigHarness.Run;
import com.proofix.runner.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.proofix.runner.testutil.ConfigHarness.cites;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** java.version precedence, Boot parent layer, parent chain, Boot version, deps, services, app module. */
class MavenFactsTest {

    @TempDir
    Path tmp;

    private static final String LOCAL_PARENT =
            "<parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>1</version></parent>";

    private static String bootParent(String v) {
        return "<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>"
                + "<version>" + v + "</version><relativePath/></parent>";
    }

    private static String rootPom(String parent, String body) {
        return "<project>\n" + parent + "\n<groupId>com.acme</groupId><artifactId>acme-parent</artifactId>"
                + "<version>1</version><packaging>pom</packaging>\n<modules><module>api</module></modules>\n"
                + body + "\n</project>\n";
    }

    private static String modulePom(String body) {
        return "<project>\n" + LOCAL_PARENT + "\n<artifactId>api</artifactId>\n" + body + "\n</project>\n";
    }

    private Path repo(String root, String api) {
        Path r = tmp.resolve("repo");
        Fixtures.write(r, "pom.xml", root);
        Fixtures.write(r, "api/pom.xml", api);
        return r;
    }

    private static String props(String... kv) {
        StringBuilder sb = new StringBuilder("<properties>\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<").append(kv[i]).append(">").append(kv[i + 1]).append("</").append(kv[i]).append(">\n");
        }
        return sb.append("</properties>").toString();
    }

    private static String compiler(String config) {
        return "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration>" + config + "</configuration></plugin></plugins></build>";
    }

    private static List<String> overriddenValues(Fact f) {
        return f.overridden().stream().map(ValueWithEvidence::value).toList();
    }

    // --- java.version -------------------------------------------------------------------------

    @Test
    void parentJavaVersion17ChildRelease21IsKnown21() {
        Run r = ConfigHarness.run(repo(rootPom("", props("java.version", "17")),
                modulePom(props("maven.compiler.release", "21"))));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals("21", f.value());
        assertEquals(List.of("17"), overriddenValues(f));
        assertTrue(cites(f, "api/pom.xml", 5));
        assertEquals("pom.xml", f.overridden().get(0).evidence().get(0).path());
    }

    @Test
    void compilerPluginReleaseBeatsEveryProperty() {
        Run r = ConfigHarness.run(repo(rootPom("", ""),
                modulePom(props("java.version", "17") + "\n" + compiler("<release>21</release>"))));
        Fact f = r.one("api", "java.version");
        assertEquals("21", f.value());
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals(List.of("17"), overriddenValues(f));
        assertTrue(cites(f, JavaVersionResolver.RULE_PLUGIN));
    }

    @Test
    void compilerPluginInheritedFromLocalParentCountsButPluginManagementDoesNot() {
        Run inherited = ConfigHarness.run(repo(rootPom("", compiler("<release>21</release>")),
                modulePom(props("maven.compiler.release", "17"))));
        Fact f = inherited.one("api", "java.version");
        assertEquals("21", f.value());
        assertTrue(cites(f, JavaVersionResolver.RULE_PLUGIN_INHERITED));
        assertEquals(List.of("17"), overriddenValues(f));

        tmp.resolve("repo").toFile().renameTo(tmp.resolve("old").toFile());
        String managed = "<build><pluginManagement><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><release>21</release></configuration></plugin></plugins></pluginManagement></build>";
        Run m = ConfigHarness.run(repo(rootPom("", managed), modulePom(props("maven.compiler.release", "17"))));
        assertEquals("17", m.one("api", "java.version").value());
    }

    @Test
    void pluginValuesResolveThroughProperties() {
        Run r = ConfigHarness.run(repo(rootPom("", props("jdk", "21")),
                modulePom(compiler("<release>${jdk}</release>"))));
        Fact f = r.one("api", "java.version");
        assertEquals("21", f.value());
        assertTrue(cites(f, "pom.xml", 6), "the property line used is cited");
    }

    @Test
    void unresolvablePropertyIsUnresolvedWithReason() {
        Run r = ConfigHarness.run(repo(rootPom("", ""), modulePom(props("maven.compiler.release", "${missing}"))));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertNull(f.value());
        assertEquals("references property missing", f.reason());
    }

    @Test
    void cyclicPropertiesAreUnresolved() {
        Run r = ConfigHarness.run(repo(rootPom("", ""),
                modulePom(props("a", "${b}", "b", "${a}", "java.version", "${a}"))));
        assertEquals(FactStatus.UNRESOLVED, r.one("api", "java.version").status());
    }

    @Test
    void sourceTargetDisagreementIsConflict() {
        Run r = ConfigHarness.run(repo(rootPom("", ""),
                modulePom(props("maven.compiler.source", "11", "maven.compiler.target", "17"))));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.CONFLICT, f.status());
        assertEquals(List.of("11", "17"), f.candidates().stream().map(ValueWithEvidence::value).toList());
    }

    @Test
    void legacyOnePointEightIsEight() {
        Run r = ConfigHarness.run(repo(rootPom("", ""),
                modulePom(props("maven.compiler.source", "1.8", "maven.compiler.target", "1.8"))));
        Fact f = r.one("api", "java.version");
        assertEquals("8", f.value());
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals(2, f.evidence().size(), "matching source and target are both cited");
    }

    @Test
    void nothingDeclaredWithoutParentIsUnresolved() {
        Run r = ConfigHarness.run(repo(rootPom("", ""), modulePom("")));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertEquals("no Java version declared", f.reason());
    }

    // --- Boot parent layer ------------------------------------------------------------------

    @Test
    void bootParentDefaultIsDefaulted17() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("3.5.0"), ""), modulePom("")));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.DEFAULTED, f.status());
        assertEquals("17", f.value());
        assertEquals("default of spring-boot-starter-parent 3.5.0", f.reason());
        assertTrue(cites(f, PropertyLayers.RULE_BOOT_DEFAULT));
        assertTrue(cites(f, "pom.xml", 2));
    }

    @Test
    void childJavaVersionFeedsTheBootParentRelease() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("3.5.0"), ""), modulePom(props("java.version", "21"))));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals("21", f.value());
        assertTrue(overriddenValues(f).contains("17"), "Boot's own java.version lost");
    }

    @Test
    void bootParentReleaseBeatsChildSource() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("3.2.5"), ""),
                modulePom(props("maven.compiler.source", "11"))));
        Fact f = r.one("api", "java.version");
        assertEquals("17", f.value());
        assertEquals(FactStatus.DEFAULTED, f.status());
        assertTrue(overriddenValues(f).contains("11"));
    }

    @Test
    void boot30UsesSourceTargetLayout() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("3.0.5"), ""), modulePom("")));
        Fact f = r.one("api", "java.version");
        assertEquals("17", f.value());
        assertEquals(FactStatus.DEFAULTED, f.status());
    }

    @Test
    void unverifiedBootVersionsAreUnresolved() {
        for (String v : new String[] {"2.7.18", "3.1.4"}) {
            Path r0 = tmp.resolve("repo");
            r0.toFile().renameTo(tmp.resolve("old-" + v).toFile());
            Run r = ConfigHarness.run(repo(rootPom(bootParent(v), ""), modulePom("")));
            Fact f = r.one("api", "java.version");
            assertEquals(FactStatus.UNRESOLVED, f.status(), v);
            assertTrue(f.reason().contains("not verified"), v);
        }
    }

    @Test
    void unverifiedBootVersionStillHonoursALocalRelease() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("2.7.18"), ""),
                modulePom(props("maven.compiler.release", "17"))));
        assertEquals(FactStatus.KNOWN, r.one("api", "java.version").status());
    }

    // --- remote / outside / missing parents -------------------------------------------------

    private static final String REMOTE_PARENT =
            "<parent><groupId>com.corp</groupId><artifactId>corp-parent</artifactId><version>9</version><relativePath/></parent>";

    @Test
    void remoteNonBootParentMakesLowerRankedKeysUnresolved() {
        Run r = ConfigHarness.run(repo(rootPom(REMOTE_PARENT, ""), modulePom(props("java.version", "17"))));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertEquals("remote parent not read; may set a higher-precedence key", f.reason());
        assertTrue(cites(f, JavaVersionResolver.RULE_UNREAD_PARENT));
    }

    @Test
    void remoteParentDoesNotOverrideALocalRelease() {
        Run r = ConfigHarness.run(repo(rootPom(REMOTE_PARENT, ""), modulePom(props("maven.compiler.release", "21"))));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.KNOWN, f.status());
        assertEquals("21", f.value());
    }

    @Test
    void relativePathOutsideTheRepoStops() {
        Path r0 = tmp.resolve("repo");
        Fixtures.write(tmp, "outside/pom.xml", rootPom("", props("java.version", "8")));
        Fixtures.write(r0, "pom.xml", "<project>\n<parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId>"
                + "<version>1</version><relativePath>../outside/pom.xml</relativePath></parent>\n<artifactId>app</artifactId>\n</project>\n");
        Run r = ConfigHarness.run(r0);
        assertTrue(r.warned(WarningCodes.PARENT_OUTSIDE_REPO, "pom.xml"));
        Fact f = r.one(".", "java.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertEquals(ParentChain.OUTSIDE, f.reason());
    }

    @Test
    void deepRelativePathOutsideTheRepoStops() {
        Run r = ConfigHarness.run(repo(rootPom("", ""), "<project>\n<parent><groupId>com.acme</groupId>"
                + "<artifactId>acme-parent</artifactId><version>1</version><relativePath>../../outside</relativePath>"
                + "</parent>\n<artifactId>api</artifactId>\n</project>\n"));
        assertTrue(r.warned(WarningCodes.PARENT_OUTSIDE_REPO, "api/pom.xml"));
    }

    @Test
    void explicitRelativePathThatIsMissingWarns() {
        Run r = ConfigHarness.run(repo(rootPom("", ""), "<project>\n<parent><groupId>com.acme</groupId>"
                + "<artifactId>acme-parent</artifactId><version>1</version><relativePath>../nope</relativePath>"
                + "</parent>\n<artifactId>api</artifactId>\n</project>\n"));
        assertTrue(r.warned(WarningCodes.PARENT_NOT_FOUND, "api/pom.xml"));
        assertEquals(ParentChain.REMOTE, r.one("api", "java.version").reason());
    }

    @Test
    void relativePathToADirectoryFindsItsPom() {
        Path r0 = tmp.resolve("repo");
        Fixtures.write(r0, "pom.xml", rootPom("", "<modules><module>parent</module><module>app</module></modules>"));
        Fixtures.write(r0, "parent/pom.xml", "<project><groupId>com.acme</groupId><artifactId>build-parent</artifactId>"
                + "<version>1</version><packaging>pom</packaging>" + props("java.version", "21") + "</project>");
        Fixtures.write(r0, "app/pom.xml", "<project><parent><groupId>com.acme</groupId><artifactId>build-parent</artifactId>"
                + "<version>1</version><relativePath>../parent</relativePath></parent><artifactId>app</artifactId></project>");
        Run r = ConfigHarness.run(r0);
        assertEquals("21", r.one("app", "java.version").value());
    }

    @Test
    void localPomWithOtherCoordinatesIsNotTheParent() {
        Run r = ConfigHarness.run(repo(rootPom("", props("java.version", "21")),
                "<project>\n<parent><groupId>com.other</groupId><artifactId>x</artifactId><version>1</version></parent>\n"
                        + "<artifactId>api</artifactId>\n</project>\n"));
        Fact f = r.one("api", "java.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertEquals(ParentChain.REMOTE, f.reason());
    }

    @Test
    void unparseableModulePomWarnsAndYieldsNoFacts() {
        Run r = ConfigHarness.run(repo(rootPom("", ""), "<project><artifactId>api</b></project>"));
        assertTrue(r.warned(WarningCodes.MARKER_UNPARSEABLE, "api/pom.xml"));
        assertTrue(r.all("api", "java.version").isEmpty());
    }

    @Test
    void doctypeModulePomIsRejected() {
        Run r = ConfigHarness.run(repo(rootPom("", ""),
                "<!DOCTYPE project [<!ENTITY x \"21\">]><project><artifactId>api</artifactId></project>"));
        assertTrue(r.warned(WarningCodes.MARKER_UNPARSEABLE, "api/pom.xml"));
    }

    // --- spring.boot.version ----------------------------------------------------------------

    private static final String BOM = "<dependencyManagement><dependencies><dependency>"
            + "<groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>"
            + "<version>${boot.v}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>";

    @Test
    void bootVersionFromStarterParent() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("3.5.0"), ""), modulePom("")));
        Fact f = r.one("api", "spring.boot.version");
        assertEquals("3.5.0", f.value());
        assertTrue(cites(f, BootVersion.RULE_PARENT));
    }

    @Test
    void bootVersionFromBomImportResolvesItsProperty() {
        Run r = ConfigHarness.run(repo(rootPom("", props("boot.v", "3.4.1") + "\n" + BOM), modulePom("")));
        Fact f = r.one("api", "spring.boot.version");
        assertEquals("3.4.1", f.value());
        assertTrue(cites(f, BootVersion.RULE_BOM));
        assertEquals("no Java version declared", r.one("api", "java.version").reason(),
                "a BOM does not set a Java version");
    }

    @Test
    void parentAndBomDisagreeingIsUnresolved() {
        Run r = ConfigHarness.run(repo(rootPom(bootParent("3.5.0"), props("boot.v", "3.4.1") + "\n" + BOM),
                modulePom("")));
        Fact f = r.one("api", "spring.boot.version");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertTrue(cites(f, BootVersion.RULE_PARENT));
        assertTrue(cites(f, BootVersion.RULE_BOM));
    }

    // --- dependencies, services, app module -------------------------------------------------

    private static String dep(String g, String a, String extra) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>" + extra + "</dependency>\n";
    }

    @Test
    void dependenciesAndServices() {
        String rootDeps = "<dependencies>\n" + dep("org.springframework.boot", "spring-boot-starter-data-redis", "")
                + "</dependencies>";
        String apiBody = props("pg.v", "42.7.4") + "\n<dependencies>\n"
                + dep("org.postgresql", "postgresql", "<version>${pg.v}</version>")
                + dep("org.junit.jupiter", "junit-jupiter", "<scope>test</scope>")
                + dep("com.h2database", "h2", "<version>${nope}</version>")
                + "</dependencies>";
        Run r = ConfigHarness.run(repo(rootPom("", rootDeps), modulePom(apiBody)));
        assertEquals(List.of("com.h2database:h2", "org.postgresql:postgresql:42.7.4",
                "org.springframework.boot:spring-boot-starter-data-redis"), r.values("api", "dependency"));
        Fact redis = r.all("api", "dependency").stream()
                .filter(f -> f.value().contains("redis")).findFirst().orElseThrow();
        assertTrue(cites(redis, MavenFacts.RULE_DEP_INHERITED));
        assertEquals(List.of("POSTGRESQL", "REDIS"), r.values("api", "service"));
        Fact pg = r.all("api", "service").stream().filter(f -> f.value().equals("POSTGRESQL")).findFirst().orElseThrow();
        assertTrue(cites(pg, "api/pom.xml", 8), "service cites the dependency line");
    }

    private static final String BOOT_PLUGIN = "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
            + "<artifactId>spring-boot-maven-plugin</artifactId>%s</plugin></plugins></build>";

    @Test
    void appModuleDeclaredWithMainClass() {
        Run r = ConfigHarness.run(repo(rootPom("", ""), modulePom(String.format(BOOT_PLUGIN,
                "<configuration><mainClass>com.acme.Api</mainClass></configuration>"))));
        Fact app = r.one("api", "app.module");
        assertTrue(cites(app, MavenFacts.RULE_PLUGIN_DECLARED));
        assertEquals("com.acme.Api", r.one("api", "entry.mainClass").value());
    }

    @Test
    void appModuleInheritedOnlyForJarChildren() {
        Path r0 = tmp.resolve("repo");
        Fixtures.write(r0, "pom.xml", rootPom("", String.format(BOOT_PLUGIN, "")
                + "<modules><module>api</module><module>agg</module></modules>"));
        Fixtures.write(r0, "api/pom.xml", modulePom(""));
        Fixtures.write(r0, "agg/pom.xml", "<project>" + LOCAL_PARENT + "<artifactId>agg</artifactId>"
                + "<packaging>pom</packaging></project>");
        Run r = ConfigHarness.run(r0);
        Fact app = r.one("api", "app.module");
        assertTrue(cites(app, MavenFacts.RULE_PLUGIN_INHERITED));
        assertTrue(cites(app, "pom.xml", 5));
        assertTrue(r.all("agg", "app.module").isEmpty());
        assertTrue(r.all(".", "app.module").isEmpty(), "pom packaging never counts");
        assertTrue(r.all("api", "entry.mainClass").isEmpty(), "no mainClass configured: not guessed");
    }

    @Test
    void pluginManagementBootPluginDoesNotMakeAnAppModule() {
        String managed = "<build><pluginManagement><plugins><plugin><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></pluginManagement></build>";
        Run r = ConfigHarness.run(repo(rootPom("", managed), modulePom("")));
        assertFalse(r.all("api", "app.module").stream().findAny().isPresent());
    }
}

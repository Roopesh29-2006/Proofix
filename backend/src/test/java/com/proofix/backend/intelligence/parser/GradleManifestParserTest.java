package com.proofix.backend.intelligence.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.proofix.backend.intelligence.model.DependencyFinding;
import com.proofix.backend.intelligence.model.DependencyFinding.VersionStatus;
import com.proofix.backend.intelligence.model.DependencyManifestParseResult;
import com.proofix.backend.intelligence.model.ParseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GradleManifestParserTest {

    private GradleManifestParser parser;

    @BeforeEach
    void setUp() {
        parser = new GradleManifestParser();
    }

    @Test
    void supportsRootAndNestedGradleFiles() {
        assertTrue(parser.supports("build.gradle"));
        assertTrue(parser.supports("build.gradle.kts"));
        assertTrue(parser.supports("modules\\api\\BUILD.GRADLE"));
        assertTrue(parser.supports("modules/api/BUILD.GRADLE.KTS"));
    }

    @Test
    void rejectsUnsupportedPaths() {
        DependencyManifestParseResult result =
                parser.parse("pom.xml", "dependencies {}");

        assertEquals(ParseStatus.UNSUPPORTED_PATH, result.status());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void handlesNullAndEmptyContent() {
        DependencyManifestParseResult nullResult =
                parser.parse("build.gradle", null);
        DependencyManifestParseResult emptyResult =
                parser.parse("build.gradle.kts", " \n ");

        assertEquals(ParseStatus.NULL_CONTENT, nullResult.status());
        assertEquals(ParseStatus.EMPTY_CONTENT, emptyResult.status());
        assertFalse(nullResult.warnings().isEmpty());
        assertFalse(emptyResult.warnings().isEmpty());
    }

    @Test
    void extractsGroovyDependencies() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle",
                """
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web:3.4.2'
                    runtimeOnly 'org.postgresql:postgresql:42.7.4'
                    testImplementation 'org.springframework.boot:starter'
                }
                """);

        assertEquals(3, result.dependencies().size());
        assertDependency(
                result.dependencies().get(0),
                "implementation",
                "org.springframework.boot",
                "spring-boot-starter-web",
                "3.4.2",
                VersionStatus.EXPLICIT);
        assertDependency(
                result.dependencies().get(1),
                "runtimeOnly",
                "org.postgresql",
                "postgresql",
                "42.7.4",
                VersionStatus.EXPLICIT);
        assertDependency(
                result.dependencies().get(2),
                "testImplementation",
                "org.springframework.boot",
                "starter",
                null,
                VersionStatus.MISSING);
    }

    @Test
    void extractsKotlinDependenciesAndPreservesNestedPath() {
        DependencyManifestParseResult result = parser.parse(
                "modules/api/build.gradle.kts",
                """
                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-web:3.4.2")
                    api("com.example:api:1.0")
                    compileOnly("com.example:annotations")
                    runtimeOnly("org.postgresql:postgresql:42.7.4")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
                }
                """);

        assertEquals(6, result.dependencies().size());
        assertEquals(
                "modules/api/build.gradle.kts",
                result.dependencies().get(0).evidence().repositoryPath());
        assertEquals("api", result.dependencies().get(1).scope());
        assertEquals("compileOnly", result.dependencies().get(2).scope());
        assertEquals("testRuntimeOnly", result.dependencies().get(5).scope());
    }

    @Test
    void classifiesVersionsWithoutResolvingThem() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle",
                """
                dependencies {
                    implementation 'com.example:explicit:3.4.2'
                    implementation 'com.example:property:${springVersion}'
                    implementation 'com.example:missing'
                    implementation 'com.example:dynamic:1.+'
                    implementation 'com.example:malformed:${springVersion'
                    implementation 'com.example:kotlin:$springVersion'
                }
                """);

        List<DependencyFinding> dependencies = result.dependencies();
        assertEquals(6, dependencies.size());
        assertEquals(VersionStatus.EXPLICIT, dependencies.get(0).versionStatus());
        assertEquals(
                VersionStatus.PROPERTY_REFERENCE,
                dependencies.get(1).versionStatus());
        assertEquals(VersionStatus.MISSING, dependencies.get(2).versionStatus());
        assertEquals(
                VersionStatus.UNRESOLVED,
                dependencies.get(3).versionStatus());
        assertEquals(
                VersionStatus.UNRESOLVED,
                dependencies.get(4).versionStatus());
        assertEquals(
                VersionStatus.UNRESOLVED,
                dependencies.get(5).versionStatus());
    }

    @Test
    void ignoresCommentsAndUnrelatedStrings() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle",
                """
                // implementation 'fake:comment:1.0'
                def text = "implementation 'fake:string:1.0'"
                /*
                 * runtimeOnly 'fake:block-comment:1.0'
                 */
                dependencies {
                    implementation 'real:library:1.0'
                }
                """);

        assertEquals(1, result.dependencies().size());
        assertEquals("real", result.dependencies().get(0).groupId());
    }

    @Test
    void ignoresDependencyLikeDeclarationsInLaterTopLevelGroovyBlocks() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle",
                """
                dependencies {
                    implementation 'real:library:1.0'
                }
                repositories {
                    implementation 'fake:repository:2.0'
                }
                configurations {
                    runtimeOnly 'fake:configuration:3.0'
                }
                """);

        assertEquals(1, result.dependencies().size());
        assertEquals("real", result.dependencies().get(0).groupId());
    }

    @Test
    void ignoresDependencyLikeDeclarationsInLaterTopLevelKotlinBlocks() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle.kts",
                """
                dependencies {
                    implementation("real:library:1.0")
                }
                repositories {
                    implementation("fake:repository:2.0")
                }
                tasks {
                    testImplementation("fake:task:3.0")
                }
                """);

        assertEquals(1, result.dependencies().size());
        assertEquals("real", result.dependencies().get(0).groupId());
    }

    @Test
    void ignoresDependencyLikeDeclarationsInNestedBlocks() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle",
                """
                dependencies {
                    constraints {
                        implementation 'fake:constraint:1.0'
                    }
                    implementation 'real:library:1.0'
                }
                """);

        assertEquals(1, result.dependencies().size());
        assertEquals("real", result.dependencies().get(0).groupId());
    }

    @Test
    void warnsForUnsupportedDeclarations() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle.kts",
                """
                dependencies {
                    implementation(libs.spring.boot.web)
                    implementation(platform("org.example:bom:1.0"))
                    implementation(project(":shared"))
                    implementation(myDependency)
                }
                """);

        assertTrue(result.dependencies().isEmpty());
        assertEquals(4, result.warnings().size());
    }

    @Test
    void warnsForMalformedDeclarations() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle",
                """
                dependencies {
                    implementation 'missing-group'
                    api(
                }
                """);

        assertTrue(result.dependencies().isEmpty());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void doesNotReportPluginMetadataAsDependencies() {
        DependencyManifestParseResult result = parser.parse(
                "build.gradle.kts",
                """
                plugins {
                    id("org.springframework.boot") version "3.4.2"
                    kotlin("jvm") version "2.0.0"
                }
                """);

        assertTrue(result.dependencies().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void registrySelectsGradleParserForBothFormats() {
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        assertEquals(
                ParseStatus.SUCCESS,
                registry.parse(
                        "build.gradle",
                        "dependencies { implementation 'g:a:1.0' }").status());
        assertEquals(
                ParseStatus.SUCCESS,
                registry.parse(
                        "modules/api/build.gradle.kts",
                        "dependencies { implementation(\"g:a:1.0\") }").status());
    }

    private static void assertDependency(
            DependencyFinding finding,
            String scope,
            String groupId,
            String artifactId,
            String version,
            VersionStatus versionStatus) {
        assertEquals("gradle", finding.ecosystem());
        assertEquals(scope, finding.scope());
        assertEquals(groupId, finding.groupId());
        assertEquals(artifactId, finding.artifactId());
        assertEquals(version, finding.version());
        assertEquals(versionStatus, finding.versionStatus());
        assertEquals(null, finding.optional());
    }
}

package com.proofix.backend.intelligence.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.proofix.backend.intelligence.model.DependencyFinding.VersionStatus;
import com.proofix.backend.intelligence.model.DependencyManifestParseResult;
import com.proofix.backend.intelligence.model.ParseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MavenManifestParserTest {

    private MavenManifestParser parser;

    @BeforeEach
    void setUp() {
        parser = new MavenManifestParser();
    }

    @Test
    void supportsRootAndNestedPomFiles() {
        assertTrue(parser.supports("pom.xml"));
        assertTrue(parser.supports("modules/api/pom.xml"));
        assertTrue(parser.supports("modules\\api\\POM.XML"));
    }

    @Test
    void rejectsUnsupportedXmlFilename() {
        DependencyManifestParseResult result =
                parser.parse("application.xml", projectXml(""));

        assertEquals(ParseStatus.UNSUPPORTED_PATH, result.status());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void extractsJavaPropertiesAndCompilerPluginValues() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                projectXml("""
                        <properties>
                          <java.version>21</java.version>
                          <maven.compiler.source>${java.version}</maven.compiler.source>
                          <maven.compiler.release>21</maven.compiler.release>
                        </properties>
                        <build>
                          <plugins>
                            <plugin>
                              <artifactId>maven-compiler-plugin</artifactId>
                              <configuration>
                                <target>21</target>
                              </configuration>
                            </plugin>
                          </plugins>
                        </build>
                        """));

        assertEquals(ParseStatus.SUCCESS, result.status());
        assertEquals("21", result.properties().get("java.version"));
        assertEquals(
                "${java.version}",
                result.javaVersionCandidates().get("maven.compiler.source"));
        assertEquals(
                "21",
                result.javaVersionCandidates().get("compiler.plugin.target"));
        assertEquals("21", result.selectedJavaVersion());
    }

    @Test
    void extractsSpringBootParentAndPlugin() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                projectXml("""
                        <parent>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-parent</artifactId>
                          <version>${spring-boot.version}</version>
                        </parent>
                        <build>
                          <plugins>
                            <plugin>
                              <artifactId>spring-boot-maven-plugin</artifactId>
                              <version>3.5.0</version>
                            </plugin>
                          </plugins>
                        </build>
                        """));

        assertEquals(
                "${spring-boot.version}",
                result.springBootParent().version());
        assertEquals(
                VersionStatus.PROPERTY_REFERENCE,
                result.springBootParent().versionStatus());
        assertEquals("3.5.0", result.springBootPlugin().version());
        assertEquals(
                VersionStatus.EXPLICIT,
                result.springBootPlugin().versionStatus());
    }

    @Test
    void extractsMultipleDirectDependenciesAndFields() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                projectXml("""
                        <dependencies>
                          <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                            <version>3.5.0</version>
                            <scope>runtime</scope>
                            <optional>true</optional>
                            <classifier>linux-x86</classifier>
                            <type>jar</type>
                          </dependency>
                          <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>${postgres.version}</version>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>managed</artifactId>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>unresolved</artifactId>
                            <version>${broken</version>
                          </dependency>
                        </dependencies>
                        """));

        assertEquals(4, result.dependencies().size());
        assertEquals(
                VersionStatus.EXPLICIT,
                result.dependencies().get(0).versionStatus());
        assertEquals("runtime", result.dependencies().get(0).scope());
        assertEquals(true, result.dependencies().get(0).optional());
        assertEquals("linux-x86", result.dependencies().get(0).classifier());
        assertEquals("jar", result.dependencies().get(0).type());
        assertEquals(
                VersionStatus.PROPERTY_REFERENCE,
                result.dependencies().get(1).versionStatus());
        assertEquals(
                VersionStatus.MISSING,
                result.dependencies().get(2).versionStatus());
        assertEquals(
                VersionStatus.UNRESOLVED,
                result.dependencies().get(3).versionStatus());
        assertEquals("pom.xml",
                result.dependencies().get(0).evidence().repositoryPath());
    }

    @Test
    void doesNotReportDependencyManagementAsDirectDependencies() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                projectXml("""
                        <dependencyManagement>
                          <dependencies>
                            <dependency>
                              <groupId>managed</groupId>
                              <artifactId>only-managed</artifactId>
                              <version>1.0</version>
                            </dependency>
                          </dependencies>
                        </dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>direct</groupId>
                            <artifactId>declared</artifactId>
                          </dependency>
                        </dependencies>
                        """));

        assertEquals(1, result.dependencies().size());
        assertEquals("direct", result.dependencies().get(0).groupId());
    }

    @Test
    void supportsEmptyAndNullContentWithStructuredStatuses() {
        DependencyManifestParseResult empty =
                parser.parse("pom.xml", " \n ");
        DependencyManifestParseResult missing =
                parser.parse("pom.xml", null);

        assertEquals(ParseStatus.EMPTY_CONTENT, empty.status());
        assertEquals(ParseStatus.NULL_CONTENT, missing.status());
        assertFalse(empty.warnings().isEmpty());
        assertFalse(missing.warnings().isEmpty());
    }

    @Test
    void reportsMalformedXml() {
        DependencyManifestParseResult result =
                parser.parse("pom.xml", "<project>");

        assertEquals(ParseStatus.MALFORMED_XML, result.status());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void supportsStandardMavenNamespace() {
        DependencyManifestParseResult result = parser.parse(
                "modules/service/pom.xml",
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>service</artifactId>
                      <version>1.2.3</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertEquals(ParseStatus.SUCCESS, result.status());
        assertEquals("modules/service/pom.xml", result.filePath());
        assertEquals(1, result.dependencies().size());
    }

    @Test
    void rejectsNamespaceFreePomByPolicy() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                "<project><dependencies/></project>");

        assertEquals(ParseStatus.UNSUPPORTED_NAMESPACE, result.status());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void rejectsUnexpectedRootNamespace() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                """
                <foreign:project xmlns:foreign="urn:foreign">
                  <foreign:dependencies/>
                </foreign:project>
                """);

        assertEquals(ParseStatus.UNSUPPORTED_NAMESPACE, result.status());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void rejectsUnexpectedNestedNamespace() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:foreign="urn:foreign">
                  <foreign:dependencies>
                    <foreign:dependency>
                      <foreign:groupId>com.example</foreign:groupId>
                      <foreign:artifactId>foreign</foreign:artifactId>
                    </foreign:dependency>
                  </foreign:dependencies>
                </project>
                """);

        assertEquals(ParseStatus.UNSUPPORTED_NAMESPACE, result.status());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void classifiesOnlyCompleteSinglePropertyReferencesAsPropertyReferences() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                projectXml("""
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>valid</artifactId>
                            <version>${spring.version}</version>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>missing-close</artifactId>
                            <version>${spring.version</version>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>missing-open</artifactId>
                            <version>spring.version}</version>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>empty</artifactId>
                            <version>${}</version>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>mixed</artifactId>
                            <version>${spring.version}-${another.version}</version>
                          </dependency>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>suffix</artifactId>
                            <version>${spring.version}suffix</version>
                          </dependency>
                        </dependencies>
                        """));

        assertEquals(
                VersionStatus.PROPERTY_REFERENCE,
                result.dependencies().get(0).versionStatus());
        assertEquals(
                "${spring.version}-${another.version}",
                result.dependencies().get(4).version());
        for (int index = 1; index < result.dependencies().size(); index++) {
            assertEquals(
                    VersionStatus.UNRESOLVED,
                    result.dependencies().get(index).versionStatus());
        }
    }

    @Test
    void rejectsDoctypeAndExternalEntityDeclarations() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                """
                <!DOCTYPE project [
                  <!ENTITY secret SYSTEM "file:///etc/passwd">
                ]>
                <project>
                  <name>&secret;</name>
                </project>
                """);

        assertEquals(ParseStatus.MALFORMED_XML, result.status());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void rejectsExternalDtdAndDoesNotAccessNetwork() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                """
                <!DOCTYPE project SYSTEM "http://127.0.0.1:9/external.dtd">
                <project/>
                """);

        assertEquals(ParseStatus.MALFORMED_XML, result.status());
    }

    @Test
    void disablesXInclude() {
        DependencyManifestParseResult result = parser.parse(
                "pom.xml",
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xi="http://www.w3.org/2001/XInclude">
                  <xi:include href="file:///etc/passwd"/>
                </project>
                """);

        assertEquals(ParseStatus.UNSUPPORTED_NAMESPACE, result.status());
        assertNull(result.springBootParent());
        assertTrue(result.dependencies().isEmpty());
    }

    private static String projectXml(String body) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + body
                + "</project>";
    }
}

package com.proofix.runner.config.maven;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomParserTest {

    private static Pom parse(String xml) throws Exception {
        return PomParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static final String FULL = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <parent>
                <groupId>com.acme</groupId>
                <artifactId>acme-parent</artifactId>
                <version>1.2</version>
              </parent>
              <artifactId>api</artifactId>
              <properties>
                <java.version>17</java.version>
                <db.version>${pg}</db.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.postgresql</groupId>
                  <artifactId>postgresql</artifactId>
                  <version>${db.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>3.5.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin><artifactId>managed-only</artifactId></plugin>
                  </plugins>
                </pluginManagement>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><release>21</release></configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @Test
    void readsTheUsedParts() throws Exception {
        Pom pom = parse(FULL);
        assertEquals("com.acme", pom.groupId(), "groupId inherited from parent");
        assertEquals("1.2", pom.version());
        assertEquals("api", pom.artifactId());
        assertEquals("jar", pom.packaging());
        assertEquals(2, pom.parent().line());
        assertNull(pom.parent().relativePath());
        assertEquals(new Pom.Val("17", 9), pom.properties().get("java.version"));
        assertEquals("${pg}", pom.properties().get("db.version").value());

        assertEquals(2, pom.dependencies().size());
        Pom.Dep pg = pom.dependencies().get(0);
        assertEquals("org.postgresql:postgresql", pg.ga());
        assertEquals("${db.version}", pg.version());
        assertEquals(15, pg.line());
        assertEquals("test", pom.dependencies().get(1).scope());

        Pom.Dep bom = pom.managedDependencies().get(0);
        assertEquals("pom", bom.type());
        assertEquals("import", bom.scope());

        assertEquals(1, pom.plugins().size(), "pluginManagement is not build/plugins");
        Pom.Plugin compiler = pom.plugin(Pom.DEFAULT_PLUGIN_GROUP, "maven-compiler-plugin");
        assertNotNull(compiler);
        assertEquals("21", compiler.config("release").value());
        assertEquals(44, compiler.config("release").line());
        assertNull(compiler.config("source"));
    }

    @Test
    void relativePathAbsentEmptyOrSet() throws Exception {
        assertEquals("", parse("<project><parent><artifactId>p</artifactId><relativePath/></parent></project>")
                .parent().relativePath());
        assertEquals("../x", parse("<project><parent><relativePath>../x</relativePath></parent></project>")
                .parent().relativePath());
        assertNull(parse("<project/>").parent());
    }

    @Test
    void noNamespaceIsAcceptedOtherNamespacesAreNot() throws Exception {
        assertEquals("pom", parse("<project><packaging>pom</packaging></project>").packaging());
        assertThrows(PomParser.PomException.class, () -> parse("<project xmlns=\"urn:other\"/>"));
        assertThrows(PomParser.PomException.class, () -> parse("<settings/>"));
    }

    @Test
    void foreignNamespaceChildrenAreIgnored() throws Exception {
        Pom pom = parse("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:o=\"urn:o\">"
                + "<o:artifactId>evil</o:artifactId><artifactId>good</artifactId>"
                + "<properties><o:java.version>8</o:java.version></properties></project>");
        assertEquals("good", pom.artifactId());
        assertTrue(pom.properties().isEmpty());
    }

    @Test
    void doctypeFailsWithTheFixedMessage() {
        PomParser.PomException e = assertThrows(PomParser.PomException.class,
                () -> parse("<!DOCTYPE project [<!ENTITY x \"y\">]><project>&x;</project>"));
        assertNull(e.getCause());
        assertEquals(com.proofix.runner.config.xml.SafeXml.UNPARSEABLE, e.getMessage());
    }
}

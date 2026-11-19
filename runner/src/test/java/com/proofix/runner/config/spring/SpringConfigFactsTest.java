package com.proofix.runner.config.spring;

import com.proofix.runner.facts.model.EnvVarFact;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.testutil.ConfigHarness;
import com.proofix.runner.testutil.ConfigHarness.Run;
import com.proofix.runner.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.proofix.runner.testutil.ConfigHarness.cites;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring config facts: profiles, precedence, env vars, secrets, datasource, health paths. */
class SpringConfigFactsTest {

    @TempDir
    Path tmp;

    private static final String RES = "src/main/resources/";

    private static String appPom(boolean actuator) {
        return "<project>\n<groupId>com.acme</groupId><artifactId>app</artifactId><version>1</version>\n<dependencies>\n"
                + (actuator ? "<dependency><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-actuator</artifactId></dependency>\n" : "")
                + "</dependencies>\n<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>\n</project>\n";
    }

    private Path repo(boolean actuator, String... files) {
        Path r = tmp.resolve("repo-" + UUID.randomUUID());
        Fixtures.write(r, "pom.xml", appPom(actuator));
        for (int i = 0; i < files.length; i += 2) Fixtures.write(r, files[i], files[i + 1]);
        return r;
    }

    private static String canary() {
        return "CANARY-" + UUID.randomUUID();
    }

    @Test
    void portPlaceholderDefaultAndProfiles() {
        Run r = ConfigHarness.run(repo(false,
                RES + "application.yml", "server:\n  port: ${PORT:8080}\n",
                RES + "application-prod.yml", "server:\n  port: 9090\n"));
        Fact def = r.one(".", "server.port", "default");
        assertEquals("8080", def.value());
        assertEquals(FactStatus.DEFAULTED, def.status());
        assertEquals("PORT", def.envVar());
        assertTrue(cites(def, RES + "application.yml", 2));
        Fact prod = r.one(".", "server.port", "prod");
        assertEquals("9090", prod.value());
        assertEquals(FactStatus.KNOWN, prod.status());
        EnvVarFact port = r.env("PORT");
        assertFalse(port.required());
        assertEquals("8080", port.defaultValue());
    }

    @Test
    void envVarsSecretsAndCanary() {
        String c1 = canary();
        String c2 = canary();
        Run r = ConfigHarness.run(repo(false, RES + "application.yml", """
                spring:
                  datasource:
                    password: ${DB_PASSWORD}
                    username: ${DB_USER:app}
                stripe:
                  api-key: ${STRIPE_KEY}
                admin:
                  password: %s
                jwt:
                  secret: ${JWT_SECRET:%s}
                app:
                  name: ${API_TOKEN:not-shown}
                """.formatted(c1, c2)));
        EnvVarFact db = r.env("DB_PASSWORD");
        assertTrue(db.required());
        assertNull(db.defaultValue());
        assertTrue(r.env("STRIPE_KEY").required());
        assertEquals("app", r.env("DB_USER").defaultValue());
        EnvVarFact jwt = r.env("JWT_SECRET");
        assertFalse(jwt.required());
        assertNull(jwt.defaultValue(), "secret default withheld");
        assertNull(r.env("API_TOKEN").defaultValue(), "secret-looking env name: default withheld");

        assertTrue(r.warnings().stream().anyMatch(w -> w.code().equals(WarningCodes.LITERAL_SECRET)
                && "admin.password".equals(w.key()) && w.line() == 8));
        assertTrue(r.warnings().stream().anyMatch(w -> w.code().equals(WarningCodes.LITERAL_SECRET)
                && "jwt.secret".equals(w.key())));
        String all = r.toString();
        assertFalse(all.contains(c1), "canary leaked");
        assertFalse(all.contains(c2), "canary leaked");
        assertFalse(all.contains("not-shown"));
    }

    @Test
    void propertyReferenceIsUnresolvedAndNotAnEnvVar() {
        Run r = ConfigHarness.run(repo(false, RES + "application.properties", "server.port=${app.port}\n"));
        Fact f = r.one(".", "server.port", "default");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertEquals("references property app.port", f.reason());
        assertNull(r.env("app.port"));
    }

    @Test
    void requiredEnvVarIsUnresolved() {
        Run r = ConfigHarness.run(repo(false, RES + "application.properties", "server.port=${PORT}\n"));
        Fact f = r.one(".", "server.port", "default");
        assertEquals(FactStatus.UNRESOLVED, f.status());
        assertEquals("PORT", f.envVar());
        assertTrue(r.env("PORT").required());
    }

    @Test
    void propertiesBeatYamlInTheSameProfile() {
        Run r = ConfigHarness.run(repo(false,
                RES + "application.yml", "server:\n  port: 7000\n",
                RES + "application.properties", "server.port=7001\n"));
        Fact f = r.one(".", "server.port", "default");
        assertEquals("7001", f.value());
        assertEquals(1, f.overridden().size());
        assertEquals("7000", f.overridden().get(0).value());
        assertEquals(RES + "application.yml", f.overridden().get(0).evidence().get(0).path());
    }

    @Test
    void laterYamlDocumentWins() {
        Run r = ConfigHarness.run(repo(false, RES + "application.yml", "server.port: 1\n---\nserver.port: 2\n"));
        assertEquals("2", r.one(".", "server.port", "default").value());
    }

    @Test
    void configOutsideAnAppModuleIsIgnored() {
        Path r0 = tmp.resolve("lib");
        Fixtures.write(r0, "pom.xml", "<project><groupId>g</groupId><artifactId>lib</artifactId></project>");
        Fixtures.write(r0, RES + "application.yml", "server:\n  port: 1\n");
        assertTrue(ConfigHarness.run(r0).all(".", "server.port").isEmpty());
    }

    @Test
    void malformedConfigWarnsAndOtherFilesStillCount() {
        Run r = ConfigHarness.run(repo(false,
                RES + "application.yml", "server: [\n",
                RES + "application-prod.properties", "server.port=9090\n"));
        assertTrue(r.warned(WarningCodes.CONFIG_UNPARSEABLE, RES + "application.yml"));
        assertEquals("9090", r.one(".", "server.port", "prod").value());
    }

    // --- datasource ------------------------------------------------------------------------

    @Test
    void datasourceUrlIsRebuiltAndKindDerived() {
        Run r = ConfigHarness.run(repo(false, RES + "application.properties",
                "spring.datasource.url=jdbc:postgresql://db.local:5432/shop?sslmode=require&ApplicationName=x\n"));
        Fact url = r.one(".", "spring.datasource.url", "default");
        assertEquals("jdbc:postgresql://db.local:5432/shop", url.value());
        assertEquals("POSTGRESQL", r.one(".", "spring.datasource.kind", "default").value());
        assertFalse(r.toString().contains("sslmode"));
    }

    @Test
    void datasourcePasswordInUrlIsNeverEchoed() {
        String c = canary();
        Run r = ConfigHarness.run(repo(false, RES + "application.properties",
                "spring.datasource.url=jdbc:mysql://root:" + c + "@db:3306/app\n"
                        + "#---\nspring.config.activate.on-profile=p2\n"
                        + "spring.datasource.url=jdbc:postgresql://db/app?user=u&password=" + c + "\n"));
        assertEquals("jdbc:mysql://db:3306/app", r.one(".", "spring.datasource.url", "default").value());
        assertEquals("jdbc:postgresql://db/app", r.one(".", "spring.datasource.url", "p2").value());
        assertEquals(2, r.warnings().stream().filter(w -> w.code().equals(WarningCodes.LITERAL_SECRET)
                && "spring.datasource.url".equals(w.key())).count());
        assertFalse(r.toString().contains(c));
    }

    @Test
    void placeholderPasswordInUrlIsNotALiteralSecret() {
        Run r = ConfigHarness.run(repo(false, RES + "application.yml",
                "spring:\n  datasource:\n    url: jdbc:postgresql://${DB_HOST:localhost}:5432/app?password=${DB_PASS}\n"));
        Fact url = r.one(".", "spring.datasource.url", "default");
        assertEquals(FactStatus.UNRESOLVED, url.status(), "DB_PASS has no default");
        assertTrue(r.warnings().stream().noneMatch(w -> w.code().equals(WarningCodes.LITERAL_SECRET)));
        assertTrue(r.env("DB_PASS").required());
        assertEquals("localhost", r.env("DB_HOST").defaultValue());
    }

    @Test
    void urlWithEnvDefaultsIsDefaulted() {
        Run r = ConfigHarness.run(repo(false, RES + "application.yml",
                "spring:\n  datasource:\n    url: jdbc:postgresql://${DB_HOST:localhost}:5432/app\n"));
        Fact url = r.one(".", "spring.datasource.url", "default");
        assertEquals(FactStatus.DEFAULTED, url.status());
        assertEquals("jdbc:postgresql://localhost:5432/app", url.value());
    }

    @Test
    void unrecognisedUrlIsUnresolvedAndNotEchoed() {
        String c = canary();
        Run r = ConfigHarness.run(repo(false, RES + "application.properties",
                "spring.datasource.url=jdbc:oracle:thin:@" + c + ":1521/x\nspring.datasource.password=\n"));
        Fact url = r.one(".", "spring.datasource.url", "default");
        assertNull(url.value());
        assertEquals(FactStatus.UNRESOLVED, url.status());
        assertEquals("ORACLE", r.one(".", "spring.datasource.kind", "default").value());
        assertFalse(r.toString().contains(c));
    }

    @Test
    void h2MemUrl() {
        Run r = ConfigHarness.run(repo(false, RES + "application.properties",
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1\n"));
        assertEquals("jdbc:h2:mem:testdb", r.one(".", "spring.datasource.url", "default").value());
        assertEquals("H2", r.one(".", "spring.datasource.kind", "default").value());
    }

    // --- health -----------------------------------------------------------------------------

    @Test
    void healthDefaultsWithoutConfig() {
        Run r = ConfigHarness.run(repo(true));
        Fact path = r.one(".", "health.path", "default");
        assertEquals("/actuator/health", path.value());
        assertEquals(FactStatus.DEFAULTED, path.status());
        assertTrue(cites(path, SpringConfigFacts.RULE_ACTUATOR));
        Fact eff = r.one(".", "health.effectivePath", "default");
        assertEquals("/actuator/health", eff.value());
    }

    @Test
    void contextPathIsPrependedWithoutManagementPort() {
        Run r = ConfigHarness.run(repo(true, RES + "application.properties",
                "server.servlet.context-path=/api\nmanagement.endpoints.web.base-path=/manage/\n"));
        assertEquals("/manage/health", r.one(".", "health.path", "default").value());
        Fact eff = r.one(".", "health.effectivePath", "default");
        assertEquals("/api/manage/health", eff.value());
        assertEquals(FactStatus.KNOWN, eff.status());
        assertTrue(cites(eff, RES + "application.properties", 1));
        assertTrue(cites(eff, RES + "application.properties", 2));
        assertEquals("/api", r.one(".", "server.servlet.context-path", "default").value());
    }

    @Test
    void managementPortUsesManagementBasePathNotContextPath() {
        Run r = ConfigHarness.run(repo(true, RES + "application.properties",
                "server.servlet.context-path=/api\nmanagement.server.port=9001\nmanagement.server.base-path=/mgmt\n"));
        Fact eff = r.one(".", "health.effectivePath", "default");
        assertEquals("/mgmt/actuator/health", eff.value());
        assertTrue(cites(eff, RES + "application.properties", 2), "port line cited");
        assertTrue(cites(eff, RES + "application.properties", 3), "management base-path cited");
        assertFalse(cites(eff, RES + "application.properties", 1), "context-path not used");
        assertEquals("9001", r.one(".", "management.server.port", "default").value());
    }

    @Test
    void managementPortWithoutBasePath() {
        Run r = ConfigHarness.run(repo(true, RES + "application.yml",
                "server:\n  servlet:\n    context-path: /api\nmanagement:\n  server:\n    port: 9001\n"));
        assertEquals("/actuator/health", r.one(".", "health.effectivePath", "default").value());
    }

    @Test
    void rootBasePathGivesSlashHealth() {
        Run r = ConfigHarness.run(repo(true, RES + "application.properties", "management.endpoints.web.base-path=/\n"));
        assertEquals("/health", r.one(".", "health.path", "default").value());
        assertEquals("/health", r.one(".", "health.effectivePath", "default").value());
    }

    @Test
    void profileThatChangesAnInputGetsItsOwnHealthFact() {
        Run r = ConfigHarness.run(repo(true,
                RES + "application.properties", "server.servlet.context-path=/api\n",
                RES + "application-prod.properties", "management.server.port=9001\n",
                RES + "application-dev.properties", "server.port=1\n"));
        assertEquals("/api/actuator/health", r.one(".", "health.effectivePath", "default").value());
        assertEquals("/actuator/health", r.one(".", "health.effectivePath", "prod").value());
        assertTrue(r.all(".", "health.effectivePath").stream().noneMatch(f -> "dev".equals(f.profile())));
    }

    @Test
    void unresolvedInputMakesHealthUnresolved() {
        Run r = ConfigHarness.run(repo(true, RES + "application.properties", "server.servlet.context-path=${CTX}\n"));
        Fact eff = r.one(".", "health.effectivePath", "default");
        assertEquals(FactStatus.UNRESOLVED, eff.status());
        assertNull(eff.value());
        assertEquals("/actuator/health", r.one(".", "health.path", "default").value());
    }

    @Test
    void noActuatorNoHealthFacts() {
        Run r = ConfigHarness.run(repo(false, RES + "application.properties", "server.port=1\n"));
        assertTrue(r.all(".", "health.path").isEmpty());
        assertTrue(r.all(".", "health.effectivePath").isEmpty());
    }

    @Test
    void segments() {
        assertEquals(List.of("", "", "/a", "/a/b"), List.of(SpringConfigFacts.segment(""),
                SpringConfigFacts.segment("/"), SpringConfigFacts.segment("a/"), SpringConfigFacts.segment("/a/b/")));
    }
}

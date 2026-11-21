package com.proofix.runner.source;

import com.proofix.runner.facts.model.Endpoint;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.scan.Limits;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;
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

/** Endpoint discovery from small generated repos (the full fixture is QuickcartEndpointsFixtureTest). */
class EndpointExtractorTest {

    @TempDir
    Path tmp;

    static final String J = "src/main/java/com/q/";

    static String pom(boolean app) {
        return "<project>\n<groupId>com.q</groupId><artifactId>app</artifactId><version>1</version>\n"
                + (app ? "<build><plugins><plugin><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>\n" : "")
                + "</project>\n";
    }

    private Path repo(boolean app, String... files) {
        Path r = tmp.resolve("repo-" + UUID.randomUUID());
        Fixtures.write(r, "pom.xml", pom(app));
        for (int i = 0; i < files.length; i += 2) Fixtures.write(r, files[i], files[i + 1]);
        return r;
    }

    @Test
    void contextPathClassAndMethodPathsAreJoined() {
        Run r = ConfigHarness.run(repo(true,
                "src/main/resources/application.yml", "server:\n  servlet:\n    context-path: /shop/\n",
                J + "A.java", """
                        package com.q;
                        @RestController
                        @RequestMapping("api/")
                        class A {
                          @GetMapping("/{id:\\\\d+}/") Object g() { return null; }
                          @DeleteMapping({"", "/"}) void d() {}
                        }
                        """));
        Endpoint g = r.endpoint("GET", "/shop/api/{id:\\d+}");
        assertEquals(List.of("id"), g.pathVariables());
        assertEquals("com.q.A.g", g.handler());
        assertEquals(".", g.module());
        assertEquals(FactStatus.KNOWN, g.status());
        assertNull(g.reason());
        assertTrue(cites(g.evidence(), EndpointExtractor.RULE_CLASS, J + "A.java", 3));
        assertTrue(cites(g.evidence(), EndpointExtractor.RULE_HANDLER, J + "A.java", 5));
        assertTrue(cites(g.evidence(), EndpointExtractor.RULE_CONTEXT, "src/main/resources/application.yml", 3));
        // "" and "/" give the same endpoint: an exact duplicate, merged.
        assertEquals(1, r.endpoints("DELETE", "/shop/api").size());
        assertTrue(r.endpointsComplete());
    }

    @Test
    void noContextPathNoPrefixRootStaysSlash() {
        Run r = ConfigHarness.run(repo(true, J + "A.java",
                "@Controller class A { @GetMapping(\"/\") void root() {} @RequestMapping(\"/x\") void x() {} }"));
        assertNull(r.endpoint("GET", "/").reason());
        assertEquals(FactStatus.KNOWN, r.endpoint("ANY", "/x").status());
        // No class @RequestMapping: the class line is the @Controller line.
        assertTrue(cites(r.endpoint("GET", "/").evidence(), EndpointExtractor.RULE_CLASS, J + "A.java", 1));
    }

    @Test
    void unresolvedContextPathAndLibraryModuleWithoutApp() {
        Run r = ConfigHarness.run(repo(true,
                "src/main/resources/application.yml", "server:\n  servlet:\n    context-path: ${CTX}\n",
                J + "A.java", "@RestController class A { @GetMapping(\"/a\") void a() {} }"));
        Endpoint a = r.endpoint("GET", "/a");
        assertEquals(FactStatus.KNOWN, a.status());
        assertEquals("context path unknown", a.reason());

        Run lib = ConfigHarness.run(repo(false, J + "A.java", "@RestController class A { @GetMapping(\"/a\") void a() {} }"));
        assertEquals("context path unknown", lib.endpoint("GET", "/a").reason());
    }

    @Test
    void requestMappingArraysAndClassLevelMethods() {
        Run r = ConfigHarness.run(repo(true, J + "A.java", """
                import static org.springframework.web.bind.annotation.RequestMethod.*;
                @RestController class A {
                  @RequestMapping(value = {"/a", "/b"}, method = {GET, RequestMethod.POST}) void m() {}
                  @RequestMapping(path = "/c", method = org.springframework.web.bind.annotation.RequestMethod.HEAD) void h() {}
                  @RequestMapping(path = "/u", method = methods()) void u() {}
                }
                @RestController @RequestMapping(path = "/k", method = RequestMethod.PUT) class K {
                  @GetMapping("/g") void g() {}
                  @RequestMapping("/r") void r() {}
                }
                """));
        for (String p : List.of("/a", "/b")) {
            for (String m : List.of("GET", "POST")) assertEquals(FactStatus.KNOWN, r.endpoint(m, p).status());
        }
        assertEquals(FactStatus.KNOWN, r.endpoint("HEAD", "/c").status());
        Endpoint u = r.endpoint("ANY", "/u");
        assertEquals(FactStatus.UNRESOLVED, u.status());
        assertEquals("request method not resolvable", u.reason());
        r.endpoint("GET", "/k/g");
        r.endpoint("PUT", "/k/g");
        r.endpoint("PUT", "/k/r");
        assertTrue(r.endpoints("ANY", "/k/r").isEmpty());
    }

    @Test
    void constantInAnotherFileAndUnresolvedExpression() {
        Run r = ConfigHarness.run(repo(true,
                J + "web/Paths.java", """
                        package com.q.web;
                        public final class Paths {
                          public static final String BASE = "/v1";
                          public static final String ORDERS = BASE + "/orders";
                        }
                        """,
                J + "Unrelated.java", "package com.q; class Unrelated { static final String ORDERS_X = \"/no\"; }",
                J + "A.java", """
                        package com.q;
                        import com.q.web.Paths;
                        @RestController class A {
                          @GetMapping(Paths.ORDERS) void o() {}
                          @GetMapping(buildPath()) void b() {}
                          @PostMapping(Paths.BASE + suffix()) void c() {}
                          @GetMapping(value = "/v", path = "/p") void d() {}
                        }
                        """));
        Endpoint o = r.endpoint("GET", "/v1/orders");
        assertTrue(cites(o.evidence(), EndpointExtractor.RULE_CONSTANT, J + "web/Paths.java", 3));
        assertTrue(cites(o.evidence(), EndpointExtractor.RULE_CONSTANT, J + "web/Paths.java", 4));
        assertTrue(cites(o.evidence(), EndpointExtractor.RULE_HANDLER, J + "A.java", 4));

        List<Endpoint> unresolved = r.endpoints().stream().filter(e -> e.path() == null).toList();
        assertEquals(3, unresolved.size());
        Endpoint b = unresolved.stream().filter(e -> e.handler().endsWith(".b")).findFirst().orElseThrow();
        assertEquals(FactStatus.UNRESOLVED, b.status());
        assertEquals("buildPath()", b.rawPath());
        assertEquals("path is not a literal or resolvable constant", b.reason());
        Endpoint c = unresolved.stream().filter(e -> e.handler().endsWith(".c")).findFirst().orElseThrow();
        assertEquals("Paths.BASE + suffix()", c.rawPath());
        Endpoint d = unresolved.stream().filter(e -> e.handler().endsWith(".d")).findFirst().orElseThrow();
        assertNull(d.rawPath());
        assertEquals("value and path are both set and differ", d.reason());
    }

    @Test
    void controllerFoundByAnnotationNotByNameAndNestedController() {
        Run r = ConfigHarness.run(repo(true,
                J + "OrderApi.java", "package com.q; @RestController class OrderApi { @GetMapping(\"/o\") void o() {} }",
                J + "FakeController.java", "package com.q; class FakeController { @GetMapping(\"/no\") void n() {} }",
                J + "Outer.java", "package com.q; class Outer { @RestController static class In { @GetMapping(\"/in\") void i() {} } }"));
        assertEquals("com.q.OrderApi.o", r.endpoint("GET", "/o").handler());
        assertTrue(r.endpoints("GET", "/no").isEmpty());
        assertEquals("com.q.Outer.In.i", r.endpoint("GET", "/in").handler());
    }

    @Test
    void duplicateEndpointKeepsBothAndWarns() {
        Run r = ConfigHarness.run(repo(true,
                J + "A.java", "package com.q; @RestController class A { @GetMapping(\"/same\") void a() {} }",
                J + "B.java", "package com.q; @RestController class B {\n @GetMapping(\"/same\") void b() {} }"));
        assertEquals(2, r.endpoints("GET", "/same").size());
        List<Warning> w = r.warnings(WarningCodes.DUPLICATE_ENDPOINT);
        assertEquals(1, w.size());
        assertEquals("GET /same", w.get(0).key());
        assertEquals(J + "B.java", w.get(0).path());
        assertEquals(2, w.get(0).line());
    }

    @Test
    void notCoveredCasesMakeTheListIncomplete() {
        Run router = ConfigHarness.run(repo(true, J + "R.java",
                "package com.q; class R { RouterFunction<?> routes() { return null; } }"));
        assertTrue(router.warned(WarningCodes.ENDPOINTS_NOT_COVERED, J + "R.java"));
        assertFalse(router.endpointsComplete());

        Run jaxrs = ConfigHarness.run(repo(true, J + "J.java", "package com.q; @Path(\"/x\") class J {}"));
        assertTrue(jaxrs.warned(WarningCodes.ENDPOINTS_NOT_COVERED, J + "J.java"));

        Run iface = ConfigHarness.run(repo(true,
                J + "Api.java", "package com.q; interface Api { @GetMapping(\"/x\") Object x(); }",
                J + "Impl.java", "package com.q; @RestController class Impl implements Api { public Object x() { return null; } }"));
        assertTrue(iface.warned(WarningCodes.ENDPOINTS_NOT_COVERED, J + "Api.java"));
        assertTrue(iface.warned(WarningCodes.ENDPOINTS_NOT_COVERED, J + "Impl.java"));
        assertTrue(iface.endpoints().isEmpty());
        assertFalse(iface.endpointsComplete());

        Run kotlin = ConfigHarness.run(repo(true, "src/main/kotlin/A.kt", "class A"));
        assertTrue(kotlin.warned(WarningCodes.ENDPOINTS_NOT_COVERED, "src/main/kotlin/A.kt"));
        assertFalse(kotlin.endpointsComplete());
    }

    @Test
    void otherBackendStackAndNoMavenModuleAreIncomplete() {
        Path r = repo(true, J + "A.java", "@RestController class A { @GetMapping(\"/a\") void a() {} }");
        Fixtures.write(r, "svc/go.mod", "module x\n");
        Run run = ConfigHarness.run(r);
        assertTrue(run.warned(WarningCodes.ENDPOINTS_NOT_COVERED, "svc"));
        assertFalse(run.endpointsComplete());

        Path node = tmp.resolve("node");
        Fixtures.write(node, "README.md", "x");
        Run none = ConfigHarness.run(node);
        assertTrue(none.endpoints().isEmpty());
        assertFalse(none.endpointsComplete());
    }

    @Test
    void brokenFileIsWarnedAndOthersStillFound() {
        Run r = ConfigHarness.run(repo(true,
                J + "Broken.java", "package com.q;\n@RestController class Broken {\n  @GetMapping(\"/b\") void b( {}\n}\n",
                J + "Ok.java", "package com.q; @RestController class Ok { @GetMapping(\"/ok\") void ok() {} }"));
        r.endpoint("GET", "/ok");
        Warning w = r.warnings(WarningCodes.SOURCE_UNPARSEABLE).get(0);
        assertEquals(J + "Broken.java", w.path());
        assertEquals(3, w.line());
        assertFalse(r.endpointsComplete());
    }

    @Test
    void testSourcesAreNeverRead() {
        Run r = ConfigHarness.run(repo(true,
                "src/test/java/com/q/T.java", "@RestController class T { @GetMapping(\"/t\") void t() {} }"));
        assertTrue(r.endpoints().isEmpty());
        assertTrue(r.endpointsComplete());
    }

    @Test
    void sourceLimitIsSharedByBothPasses() {
        Path repo = repo(true,
                J + "web/Paths.java", "package com.q.web; public class Paths { public static final String P = \"/p\"; }",
                J + "A.java", "package com.q; import com.q.web.Paths; @RestController class A { @GetMapping(Paths.P) void a() {} }");
        Run ok = ConfigHarness.run(repo, Limits.DEFAULTS, 2);
        ok.endpoint("GET", "/p");
        assertTrue(ok.endpointsComplete());

        Run hit = ConfigHarness.run(repo, Limits.DEFAULTS, 1);
        assertEquals(1, hit.warnings(WarningCodes.SOURCE_LIMIT_HIT).size());
        assertFalse(hit.endpointsComplete());
        assertNull(hit.endpoints().get(0).path());
    }
}

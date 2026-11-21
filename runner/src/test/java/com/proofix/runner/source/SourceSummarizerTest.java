package com.proofix.runner.source;

import com.proofix.runner.source.SourceSummary.EnvUse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSummarizerTest {

    static SourceSummary summarize(String src) {
        SafeJavaParser.Parsed p = assertInstanceOf(SafeJavaParser.Parsed.class,
                new SafeJavaParser().parse(src.getBytes(StandardCharsets.UTF_8)));
        return SourceSummarizer.summarize(p.unit());
    }

    @Test
    void controllerMappingsAndLines() {
        SourceSummary s = summarize("""
                package com.q;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/orders/")
                class OrderApi {
                  @GetMapping("/{id}") Object get() { return null; }
                  @PostMapping Object post() { return null; }
                  @RequestMapping(value = {"/a", "/b"}, method = {RequestMethod.GET, RequestMethod.POST}) void m() {}
                  void notMapped() {}
                }
                """);
        SourceSummary.Controller c = s.controllers().get(0);
        assertEquals("com.q.OrderApi", c.canonicalName());
        assertEquals(3, c.annotationLine());
        assertEquals(4, c.classMapping().line());
        assertEquals(List.of(new Expr.Lit("/api/orders/")), c.classMapping().paths());
        assertEquals(3, c.handlers().size());
        SourceSummary.Handler get = c.handlers().get(0);
        assertEquals("get", get.method());
        assertEquals("GET", get.mapping().fixedMethod());
        assertEquals(6, get.mapping().line());
        assertTrue(c.handlers().get(1).mapping().paths().isEmpty());
        SourceSummary.Mapping rm = c.handlers().get(2).mapping();
        assertNull(rm.fixedMethod());
        assertEquals(2, rm.paths().size());
        assertEquals(List.of(new Expr.Name("RequestMethod", "GET"), new Expr.Name("RequestMethod", "POST")), rm.methods());
        assertFalse(c.hasSupertype());
    }

    @Test
    void pathExpressionsAreModelled() {
        SourceSummary s = summarize("""
                @org.springframework.stereotype.Controller
                class C {
                  @GetMapping(Paths.ORDERS) void a() {}
                  @GetMapping(path = BASE + "/x") void b() {}
                  @GetMapping(buildPath()) void c() {}
                  @GetMapping(value = "/v", path = "/p") void d() {}
                  @GetMapping(value = "/same", path = "/same") void e() {}
                }
                """);
        List<SourceSummary.Handler> h = s.controllers().get(0).handlers();
        assertEquals(List.of(new Expr.Name("Paths", "ORDERS")), h.get(0).mapping().paths());
        assertEquals(List.of(new Expr.Concat(List.of(new Expr.Name(null, "BASE"), new Expr.Lit("/x")))),
                h.get(1).mapping().paths());
        assertEquals(List.of(new Expr.Other("buildPath()")), h.get(2).mapping().paths());
        assertTrue(h.get(3).mapping().pathAmbiguous());
        assertFalse(h.get(4).mapping().pathAmbiguous());
    }

    @Test
    void constantsImportsAndNestedTypes() {
        SourceSummary s = summarize("""
                package com.q;
                import com.q.web.Paths;
                import com.q.other.*;
                import static com.q.web.Paths.ORDERS;
                import static com.q.web.More.*;
                public final class Holder {
                  public static final String A = "/a";
                  static final String B = A + "/b";
                  final String notStatic = "/n";
                  static String notFinal = "/n";
                  static final int NUM = 1;
                  interface Inner { String I = "/i"; }
                }
                """);
        assertEquals("com.q", s.packageName());
        assertEquals("com.q.web.Paths", s.imports().get("Paths"));
        assertEquals(List.of("com.q.other"), s.wildcardImports());
        assertEquals("com.q.web.Paths", s.staticImports().get("ORDERS"));
        assertEquals(List.of("com.q.web.More"), s.staticWildcards());
        assertEquals(List.of("com.q.Holder", "com.q.Holder.Inner"), s.types());
        assertEquals(List.of("A", "B", "I"), s.constants().stream().map(SourceSummary.Constant::name).toList());
        assertEquals("com.q.Holder.Inner", s.constants().get(2).owner());
        assertEquals(8, s.constants().get(1).line());
    }

    @Test
    void bootApplicationNeedsPublicStaticVoidMain() {
        SourceSummary s = summarize("""
                package a;
                @SpringBootApplication class One { public static void main(String[] args) {} }
                @org.springframework.boot.autoconfigure.SpringBootApplication
                class Two { public static void main(String... args) {} }
                @SpringBootApplication class Three { static void main(String[] args) {} }
                @SpringBootApplication class Four { public static int main(String[] args) { return 0; } }
                """);
        assertEquals(List.of(true, true, false, false), s.bootApps().stream().map(SourceSummary.BootApp::hasMain).toList());
        assertEquals(3, s.bootApps().get(1).annotationLine());
    }

    @Test
    void envUses() {
        SourceSummary s = summarize("""
                import static java.lang.System.getenv;
                class E {
                  @Value("${STRIPE_KEY}") String k;
                  E(@Value("${A:1}") String a) {}
                  @lombok.Value static class L {}
                  String x = System.getenv("DB_PASSWORD");
                  String y = System.getenv(prefix + "_KEY");
                  String z = getenv("Z");
                  Object all = System.getenv();
                  String other = Other.getenv("NO");
                }
                """);
        List<EnvUse> e = s.envUses();
        assertEquals(List.of(
                new EnvUse(EnvUse.Kind.VALUE, "${STRIPE_KEY}", 3),
                new EnvUse(EnvUse.Kind.VALUE, "${A:1}", 4),
                new EnvUse(EnvUse.Kind.GETENV, "DB_PASSWORD", 6),
                new EnvUse(EnvUse.Kind.GETENV, null, 7),
                new EnvUse(EnvUse.Kind.GETENV, "Z", 8),
                new EnvUse(EnvUse.Kind.GETENV, null, 9)), e);
    }

    @Test
    void interfaceMappingsAndSupertypes() {
        SourceSummary s = summarize("""
                interface Api { @GetMapping("/x") Object x(); }
                @RestController class Impl implements Api { public Object x() { return null; } }
                @RestController class Sub extends Base {}
                """);
        assertEquals(List.of(1), s.interfaceMappingLines());
        assertTrue(s.controllers().get(0).hasSupertype());
        assertTrue(s.controllers().get(0).handlers().isEmpty());
        assertTrue(s.controllers().get(1).hasSupertype());
    }

    @Test
    void otherTextIsCleanedAndCut() {
        assertEquals("a b", Expr.clean("a \n\t b\u0001"));
        assertEquals(Expr.MAX_TEXT, Expr.clean("x".repeat(500)).length());
    }
}

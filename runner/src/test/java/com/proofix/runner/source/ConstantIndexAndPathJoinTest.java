package com.proofix.runner.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.proofix.runner.source.SourceSummarizerTest.summarize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConstantIndexAndPathJoinTest {

    private static final String PATHS = """
            package com.q.web;
            public final class Paths {
              public static final String API = "/api";
              public static final String ORDERS = API + "/orders";
              public static final String LOOP = LOOP2 + "x";
              public static final String LOOP2 = LOOP + "y";
              public static final String CALL = String.valueOf(1);
              public static class Admin { public static final String ROOT = API + "/admin"; }
            }
            """;

    private static ConstantIndex index(String... sources) {
        ConstantIndex idx = new ConstantIndex();
        int i = 0;
        for (String s : sources) idx.add(summarize(s), "F" + (i++) + ".java", "sha");
        return idx;
    }

    private static ConstantIndex.Scope scope(String src, String cls) {
        return new ConstantIndex.Scope(summarize(src), List.of(cls));
    }

    @Test
    void qualifiedNameThroughImportResolvesAndCitesEveryConstantUsed() {
        String ctl = "package com.q.api; import com.q.web.Paths; class C {}";
        ConstantIndex idx = index(PATHS, ctl);
        ConstantIndex.Eval e = idx.eval(new Expr.Name("Paths", "ORDERS"), scope(ctl, "com.q.api.C"));
        assertEquals("/api/orders", e.value());
        assertEquals(List.of("API", "ORDERS"), e.used().stream().map(x -> x.constant().name()).toList());
        assertEquals(List.of(3, 4), e.used().stream().map(x -> x.constant().line()).toList());
    }

    @Test
    void simpleNameSamePackageStaticImportNestedAndFullyQualified() {
        String same = "package com.q.web; class C { static final String LOCAL = \"/l\"; }";
        ConstantIndex idx = index(PATHS, same);
        assertEquals("/l", idx.eval(new Expr.Name(null, "LOCAL"), scope(same, "com.q.web.C")).value());
        assertEquals("/api", idx.eval(new Expr.Name("Paths", "API"), scope(same, "com.q.web.C")).value());
        assertEquals("/api/admin", idx.eval(new Expr.Name("Paths.Admin", "ROOT"), scope(same, "com.q.web.C")).value());

        String st = "package x; import static com.q.web.Paths.API; class D {}";
        ConstantIndex idx2 = index(PATHS, st);
        assertEquals("/api", idx2.eval(new Expr.Name(null, "API"), scope(st, "x.D")).value());
        assertEquals("/api", idx2.eval(new Expr.Name("com.q.web.Paths", "API"), scope(st, "x.D")).value());

        String wild = "package y; import static com.q.web.Paths.*; import com.q.web.*; class E {}";
        ConstantIndex idx3 = index(PATHS, wild);
        assertEquals("/api", idx3.eval(new Expr.Name(null, "API"), scope(wild, "y.E")).value());
        assertEquals("/api", idx3.eval(new Expr.Name("Paths", "API"), scope(wild, "y.E")).value());
    }

    @Test
    void unresolvable() {
        String ctl = "package com.q.api; import com.q.web.Paths; class C {}";
        ConstantIndex idx = index(PATHS, ctl);
        ConstantIndex.Scope sc = scope(ctl, "com.q.api.C");
        assertFalse(idx.eval(new Expr.Name("Paths", "LOOP"), sc).ok());
        assertFalse(idx.eval(new Expr.Name("Paths", "CALL"), sc).ok());
        assertFalse(idx.eval(new Expr.Name("Paths", "MISSING"), sc).ok());
        assertFalse(idx.eval(new Expr.Name(null, "API"), sc).ok()); // not imported
        assertFalse(idx.eval(new Expr.Other("buildPath()"), sc).ok());
        assertNull(idx.eval(new Expr.Name("Nope", "X"), sc).value());
    }

    @Test
    void ambiguousWildcardOrDuplicateDoesNotResolve() {
        String a = "package p1; public class K { public static final String X = \"/1\"; }";
        String b = "package p2; public class K { public static final String X = \"/2\"; }";
        String c = "package z; import p1.*; import p2.*; class U {}";
        assertFalse(index(a, b, c).eval(new Expr.Name("K", "X"), scope(c, "z.U")).ok());
        String dup = "package p1; public class K { public static final String X = \"/3\"; }";
        String d = "package z; import p1.K; class V {}";
        assertFalse(index(a, dup, d).eval(new Expr.Name("K", "X"), scope(d, "z.V")).ok());
    }

    @Test
    void joining() {
        assertEquals("/shop/api/orders/{id}", PathJoin.join(List.of("/shop", "/api/orders/", "/{id}")));
        assertEquals("/api/orders", PathJoin.join(List.of("", "api//orders/", "")));
        assertEquals("/", PathJoin.join(List.of("/", "")));
        assertEquals("/", PathJoin.join(List.of()));
        assertEquals("/x/{id:\\d+}", PathJoin.join(List.of("x", "{id:\\d+}/")));
        assertEquals("/f/{p:[a-z]{2}/[0-9]}", PathJoin.join(List.of("/f/", "{p:[a-z]{2}/[0-9]}")));
    }

    @Test
    void variables() {
        assertEquals(List.of("id"), PathJoin.variables("/o/{id:\\d+}"));
        assertEquals(List.of("a", "b", "rest"), PathJoin.variables("/{a}/{b:[a-z]{2}}/{a}/{*rest}"));
        assertEquals(List.of(), PathJoin.variables("/plain"));
    }
}

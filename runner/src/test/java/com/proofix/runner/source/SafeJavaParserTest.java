package com.proofix.runner.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** [SEC] The parser wrapper keeps no parser message; a failure is a line number at most. */
class SafeJavaParserTest {

    private final SafeJavaParser parser = new SafeJavaParser();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesJava21Source() {
        String src = "package a;\nrecord P(int x) {}\nsealed interface S permits T {}\nfinal class T implements S {\n"
                + "  String f(Object o) { return switch (o) { case String s -> s; default -> \"\"; }; }\n}\n";
        assertInstanceOf(SafeJavaParser.Parsed.class, parser.parse(b(src)));
    }

    @Test
    void byteOrderMarkIsIgnored() {
        assertInstanceOf(SafeJavaParser.Parsed.class, parser.parse(b("﻿class A {}")));
    }

    @Test
    void failureCarriesOnlyTheLine() {
        String canary = "sk_live_" + UUID.randomUUID().toString().replace("-", "");
        String src = "package a;\n\nclass A {\n  String k = \"" + canary + "\";\n  void m( {\n}\n";
        SafeJavaParser.Failed f = assertInstanceOf(SafeJavaParser.Failed.class, parser.parse(b(src)));
        assertEquals(5, f.line());
        assertFalse(f.toString().contains(canary));
        assertFalse(SafeJavaParser.UNPARSEABLE_MESSAGE.contains(canary));
    }

    @Test
    void deeplyNestedExpressionFailsSafely() {
        String src = "class A { int x = " + "(".repeat(20000) + "1" + ")".repeat(20000) + "; }";
        // Either outcome is acceptable as long as it does not throw.
        parser.parse(b(src));
    }
}

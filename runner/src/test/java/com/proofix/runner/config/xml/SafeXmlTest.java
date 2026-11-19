package com.proofix.runner.config.xml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [SEC] DOCTYPE/XXE rejection, fixed error messages, limits and line numbers. */
class SafeXmlTest {

    @TempDir
    Path tmp;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private SafeXml.XmlException fails(String xml) {
        SafeXml.XmlException e = assertThrows(SafeXml.XmlException.class, () -> SafeXml.parse(b(xml)));
        assertNull(e.getCause());
        assertEquals(0, e.getStackTrace().length);
        return e;
    }

    @Test
    void elementsKeepTheirLines() throws Exception {
        XmlNode root = SafeXml.parse(b("<project>\n  <a>x</a>\n\n  <b>\n    <c> y </c>\n  </b>\n</project>\n"));
        assertEquals("project", root.name());
        assertEquals(2, root.child("a").line());
        assertEquals("x", root.childText("a"));
        assertEquals(5, root.child("b").child("c").line());
        assertEquals("y", root.child("b").childText("c"));
        assertEquals("", root.child("b").text());
    }

    @Test
    void externalEntityToCanaryFileIsRejectedAndNotRead() throws Exception {
        String canary = "XXE-CANARY-" + UUID.randomUUID();
        Path secret = Files.writeString(tmp.resolve("canary.txt"), canary);
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE project [<!ENTITY x SYSTEM \""
                + secret.toUri() + "\">]>\n<project><name>&x;</name></project>";
        SafeXml.XmlException e = fails(xml);
        assertEquals(SafeXml.UNPARSEABLE, e.getMessage());
        assertFalse(e.getMessage().contains(canary));
        assertFalse(e.getMessage().contains(secret.toString()));
    }

    @Test
    void parameterEntityAndExternalDtdAreRejected() throws Exception {
        Path dtd = Files.writeString(tmp.resolve("evil.dtd"), "<!ENTITY y \"z\">");
        fails("<!DOCTYPE project [<!ENTITY % p SYSTEM \"" + dtd.toUri() + "\"> %p;]><project/>");
        fails("<!DOCTYPE project SYSTEM \"" + dtd.toUri() + "\"><project/>");
    }

    @Test
    void billionLaughsIsRejectedFast() {
        StringBuilder sb = new StringBuilder("<!DOCTYPE lolz [<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            sb.append("<!ENTITY lol").append(i).append(" \"");
            for (int j = 0; j < 10; j++) sb.append("&lol").append(i == 1 ? "" : String.valueOf(i - 1)).append(';');
            sb.append("\">");
        }
        sb.append("]><project>&lol9;</project>");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> fails(sb.toString()));
    }

    @Test
    void xincludeIsNotProcessed() throws Exception {
        String canary = "XINCLUDE-CANARY-" + UUID.randomUUID();
        Path secret = Files.writeString(tmp.resolve("inc.txt"), canary);
        XmlNode root = SafeXml.parse(b("<project xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
                + "<xi:include href=\"" + secret.toUri() + "\" parse=\"text\"/></project>"));
        XmlNode inc = root.children().get(0);
        assertEquals("include", inc.name());
        assertEquals("", inc.text());
        assertNull(root.child("include"), "foreign-namespace elements are not matched by name");
    }

    @Test
    void malformedXmlMessageNeverQuotesContent() {
        String leak = "LEAK-" + UUID.randomUUID();
        SafeXml.XmlException e = fails("<project><" + leak + ">x</b></project>");
        assertEquals(SafeXml.UNPARSEABLE, e.getMessage());
        assertFalse(e.getMessage().contains(leak));
        assertEquals(SafeXml.UNPARSEABLE, fails("").getMessage());
        assertEquals(SafeXml.UNPARSEABLE, fails("not xml " + leak).getMessage());
    }

    @Test
    void depthLimit() throws Exception {
        String ok = "<a>".repeat(SafeXml.MAX_DEPTH) + "</a>".repeat(SafeXml.MAX_DEPTH);
        assertNotNull(SafeXml.parse(b(ok)));
        String deep = "<a>".repeat(SafeXml.MAX_DEPTH + 1) + "</a>".repeat(SafeXml.MAX_DEPTH + 1);
        assertEquals(SafeXml.LIMIT, fails(deep).getMessage());
    }

    @Test
    void elementCountLimit() {
        String many = "<p>" + "<x/>".repeat(SafeXml.MAX_ELEMENTS) + "</p>";
        assertEquals(SafeXml.LIMIT, fails(many).getMessage());
    }

    @Test
    void textIsTrimmedAndAbsentChildIsNull() throws Exception {
        XmlNode root = SafeXml.parse(b("<p><a>   </a></p>"));
        assertNull(root.childText("a"));
        assertNull(root.childText("missing"));
        assertTrue(root.children("missing").isEmpty());
    }
}

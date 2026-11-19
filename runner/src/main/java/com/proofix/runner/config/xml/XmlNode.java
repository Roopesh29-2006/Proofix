package com.proofix.runner.config.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A parsed XML element: local name, namespace URI ("" for none), the 1-based line of its start tag
 * (as reported by the SAX Locator, i.e. where the start tag ends), its trimmed direct text and its
 * child elements. Lookups by name only match children in this element's own namespace, so elements
 * from foreign namespaces inside a POM are ignored.
 */
public final class XmlNode {

    private final String name;
    private final String ns;
    private final int line;
    private final List<XmlNode> children = new ArrayList<>();
    private StringBuilder textBuf = new StringBuilder();
    private String text;

    XmlNode(String name, String ns, int line) {
        this.name = Objects.requireNonNull(name);
        this.ns = ns == null ? "" : ns;
        this.line = Math.max(1, line);
    }

    public String name() {
        return name;
    }

    public String ns() {
        return ns;
    }

    public int line() {
        return line;
    }

    /** Trimmed direct character data; "" when there is none. */
    public String text() {
        return text == null ? "" : text;
    }

    public List<XmlNode> children() {
        return Collections.unmodifiableList(children);
    }

    /** First child with this local name in this element's namespace, or null. */
    public XmlNode child(String childName) {
        for (XmlNode c : children) {
            if (c.name.equals(childName) && c.ns.equals(ns)) return c;
        }
        return null;
    }

    /** All children with this local name in this element's namespace. */
    public List<XmlNode> children(String childName) {
        List<XmlNode> out = new ArrayList<>();
        for (XmlNode c : children) {
            if (c.name.equals(childName) && c.ns.equals(ns)) out.add(c);
        }
        return out;
    }

    /** Text of the named child, or null when the child is absent or blank. */
    public String childText(String childName) {
        XmlNode c = child(childName);
        if (c == null || c.text().isEmpty()) return null;
        return c.text();
    }

    void add(XmlNode child) {
        children.add(child);
    }

    void appendText(char[] ch, int start, int length) {
        textBuf.append(ch, start, length);
    }

    void finish() {
        text = textBuf.toString().trim();
        textBuf = null;
    }
}

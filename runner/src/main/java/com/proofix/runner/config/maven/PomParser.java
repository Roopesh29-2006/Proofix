package com.proofix.runner.config.maven;

import com.proofix.runner.config.xml.SafeXml;
import com.proofix.runner.config.xml.XmlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a POM through {@link SafeXml}. Accepts a root {@code <project>} with no namespace or the
 * Maven 4.0.0 namespace (the backend parser requires the namespace; fixture POMs often omit it).
 */
public final class PomParser {
    private PomParser() {}

    public static final String MAVEN_NS = "http://maven.apache.org/POM/4.0.0";

    /** Fixed message only; never a cause. */
    public static final class PomException extends Exception {
        PomException(String fixedMessage) {
            super(fixedMessage, null, false, false);
        }
    }

    public static Pom parse(byte[] bytes) throws PomException {
        XmlNode root;
        try {
            root = SafeXml.parse(bytes);
        } catch (SafeXml.XmlException e) {
            throw new PomException(e.getMessage());
        }
        if (!root.name().equals("project") || !(root.ns().isEmpty() || root.ns().equals(MAVEN_NS))) {
            throw new PomException("not a Maven POM");
        }

        Pom.Parent parent = null;
        XmlNode p = root.child("parent");
        if (p != null) {
            XmlNode rel = p.child("relativePath");
            parent = new Pom.Parent(p.childText("groupId"), p.childText("artifactId"), p.childText("version"),
                    rel == null ? null : rel.text(), p.line());
        }

        Map<String, Pom.Val> props = new LinkedHashMap<>();
        XmlNode propsNode = root.child("properties");
        if (propsNode != null) {
            // A repeated key keeps the last declaration, like Maven's Properties merge.
            for (XmlNode c : propsNode.children()) {
                if (c.ns().equals(root.ns())) props.put(c.name(), new Pom.Val(c.text(), c.line()));
            }
        }

        List<Pom.Dep> deps = deps(root.child("dependencies"));
        XmlNode dm = root.child("dependencyManagement");
        List<Pom.Dep> managed = deps(dm == null ? null : dm.child("dependencies"));

        List<Pom.Plugin> plugins = new ArrayList<>();
        XmlNode build = root.child("build");
        XmlNode pluginsNode = build == null ? null : build.child("plugins");
        if (pluginsNode != null) {
            for (XmlNode pl : pluginsNode.children("plugin")) {
                String a = pl.childText("artifactId");
                if (a == null) continue;
                String g = pl.childText("groupId");
                XmlNode art = pl.child("artifactId");
                plugins.add(new Pom.Plugin(g == null ? Pom.DEFAULT_PLUGIN_GROUP : g, a, art.line(),
                        pl.child("configuration")));
            }
        }

        String groupId = root.childText("groupId");
        if (groupId == null && parent != null) groupId = parent.groupId();
        String version = root.childText("version");
        if (version == null && parent != null) version = parent.version();
        String packaging = root.childText("packaging");
        return new Pom(groupId, root.childText("artifactId"), version, packaging == null ? "jar" : packaging,
                parent, props, deps, managed, plugins);
    }

    private static List<Pom.Dep> deps(XmlNode node) {
        List<Pom.Dep> out = new ArrayList<>();
        if (node == null) return out;
        for (XmlNode d : node.children("dependency")) {
            String g = d.childText("groupId");
            String a = d.childText("artifactId");
            if (g == null || a == null) continue;
            out.add(new Pom.Dep(g, a, d.childText("version"), d.childText("scope"), d.childText("type"),
                    d.child("artifactId").line()));
        }
        return out;
    }
}

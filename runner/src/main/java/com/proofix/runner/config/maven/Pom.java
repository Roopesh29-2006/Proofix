package com.proofix.runner.config.maven;

import com.proofix.runner.config.xml.XmlNode;

import java.util.List;
import java.util.Map;

/**
 * The parts of one POM the runner uses, with 1-based lines. Values are raw text: ${...} references
 * are not resolved here. Plugins are only those under build/plugins (never pluginManagement).
 */
public record Pom(String groupId, String artifactId, String version, String packaging, Parent parent,
                  Map<String, Val> properties, List<Dep> dependencies, List<Dep> managedDependencies,
                  List<Plugin> plugins) {

    public static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    public Pom {
        properties = Map.copyOf(properties);
        dependencies = List.copyOf(dependencies);
        managedDependencies = List.copyOf(managedDependencies);
        plugins = List.copyOf(plugins);
    }

    public boolean isPomPackaging() {
        return "pom".equals(packaging);
    }

    public Plugin plugin(String groupId, String artifactId) {
        for (Plugin p : plugins) {
            if (p.groupId().equals(groupId) && p.artifactId().equals(artifactId)) return p;
        }
        return null;
    }

    /** A value and the line it was declared on. */
    public record Val(String value, int line) {}

    /** relativePath: null when the element is absent, "" when it is present but empty. */
    public record Parent(String groupId, String artifactId, String version, String relativePath, int line) {
        public boolean is(String g, String a) {
            return g.equals(groupId) && a.equals(artifactId);
        }
    }

    /** line is the line of the dependency's artifactId (falls back to the dependency element). */
    public record Dep(String groupId, String artifactId, String version, String scope, String type, int line) {
        public String ga() {
            return groupId + ":" + artifactId;
        }
    }

    /** configuration is null when the plugin has none. */
    public record Plugin(String groupId, String artifactId, int line, XmlNode configuration) {
        public Val config(String name) {
            if (configuration == null) return null;
            XmlNode c = configuration.child(name);
            if (c == null || c.text().isEmpty()) return null;
            return new Val(c.text(), c.line());
        }
    }
}

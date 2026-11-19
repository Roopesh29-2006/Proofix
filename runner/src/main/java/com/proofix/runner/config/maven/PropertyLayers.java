package com.proofix.runner.config.maven;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.facts.model.FactEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POM properties as layers, highest priority first: the module, its local parents, then (when the
 * chain ends at a verified spring-boot-starter-parent) the parent's synthetic layer. ${x} resolves
 * only through these layers; nothing else (no project.*, no system properties, no env).
 */
public final class PropertyLayers {

    public static final int MAX_DEPTH = 10;
    public static final String RULE_PROPERTY = "maven-property";
    public static final String RULE_BOOT_DEFAULT = "boot-parent-default";

    private static final Pattern REF = Pattern.compile("\\$\\{([^}]*)}");

    /** One declaration of a key. synthetic = from the Boot parent layer. */
    public record Decl(String key, String raw, FactEvidence evidence, boolean synthetic) {}

    /**
     * value is null when unresolved; missing is then the first key that could not be found (or
     * null for a cycle / depth overrun). used lists every declaration the value depended on.
     */
    public record Resolved(String value, String missing, List<Decl> used) {
        public boolean resolved() {
            return value != null;
        }

        public boolean onlySynthetic() {
            return !used.isEmpty() && used.stream().allMatch(Decl::synthetic);
        }

        public List<FactEvidence> evidence() {
            return used.stream().map(Decl::evidence).toList();
        }
    }

    private final List<Map<String, Decl>> layers = new ArrayList<>();

    public PropertyLayers(ConfigContext ctx, ParentChain chain, Map<String, String> bootLayer) {
        for (LoadedPom lp : chain.poms()) {
            Map<String, Decl> m = new TreeMap<>();
            for (Map.Entry<String, Pom.Val> e : lp.pom().properties().entrySet()) {
                m.put(e.getKey(), new Decl(e.getKey(), e.getValue().value(),
                        ctx.cite(lp.src(), RULE_PROPERTY, e.getValue().line()), false));
            }
            layers.add(m);
        }
        if (bootLayer != null && chain.end() instanceof ParentChain.BootParent bp) {
            FactEvidence at = ctx.cite(bp.at().src(), RULE_BOOT_DEFAULT, bp.at().pom().parent().line());
            Map<String, Decl> m = new TreeMap<>();
            bootLayer.forEach((k, v) -> m.put(k, new Decl(k, v, at, true)));
            layers.add(m);
        }
    }

    /** Every declaration of key, highest priority first. */
    public List<Decl> all(String key) {
        List<Decl> out = new ArrayList<>();
        for (Map<String, Decl> l : layers) {
            Decl d = l.get(key);
            if (d != null) out.add(d);
        }
        return out;
    }

    public Decl effective(String key) {
        List<Decl> a = all(key);
        return a.isEmpty() ? null : a.get(0);
    }

    /** Resolves ${...} references in raw through the layers. */
    public Resolved resolve(String raw) {
        Set<Decl> used = new LinkedHashSet<>();
        String[] missing = {null};
        String v = expand(raw, used, new LinkedHashSet<>(), 0, missing);
        return new Resolved(v, v == null ? missing[0] : null, List.copyOf(used));
    }

    /** Resolves one declaration (its own evidence counts as used). */
    public Resolved resolve(Decl d) {
        Resolved r = resolve(d.raw());
        List<Decl> used = new ArrayList<>();
        used.add(d);
        used.addAll(r.used());
        return new Resolved(r.value(), r.missing(), List.copyOf(used));
    }

    private String expand(String raw, Set<Decl> used, Set<String> active, int depth, String[] missing) {
        if (raw == null) return null;
        if (depth > MAX_DEPTH) return null;
        Matcher m = REF.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            if (active.contains(key)) return null; // cycle
            Decl d = effective(key);
            if (d == null) {
                if (missing[0] == null) missing[0] = key;
                return null;
            }
            used.add(d);
            active.add(key);
            String inner = expand(d.raw(), used, active, depth + 1, missing);
            active.remove(key);
            if (inner == null) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(inner));
        }
        m.appendTail(sb);
        String out = sb.toString();
        return out.contains("${") ? null : out;
    }
}

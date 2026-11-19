package com.proofix.runner.config.maven;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.config.maven.PropertyLayers.Resolved;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.scan.model.ScannedFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maven facts for one module POM: java.version, spring.boot.version, dependencies, services, and
 * whether it is the app module (with entry.mainClass when configured). Modules with pom packaging
 * (aggregators) yield no facts; their settings reach the children through the parent chain.
 */
public final class MavenFacts {
    private MavenFacts() {}

    public static final String BOOT_PLUGIN = "spring-boot-maven-plugin";
    public static final String RULE_DEP = "maven-dependency";
    public static final String RULE_DEP_INHERITED = "maven-dependency-inherited";
    public static final String RULE_SERVICE = "service-table";
    public static final String RULE_PLUGIN_DECLARED = "boot-plugin-declared";
    public static final String RULE_PLUGIN_INHERITED = "boot-plugin-inherited";
    public static final String RULE_MAIN_CLASS = "boot-plugin-main-class";

    /**
     * Result for one module. javaVersion is kept apart so the runtime JDK check can amend it;
     * dependencies maps non-test groupId:artifactId to the line that declares it.
     */
    public record Module(String dir, LoadedPom pom, boolean app, Fact javaVersion, List<Fact> facts,
                         Map<String, FactEvidence> dependencies) {}

    /** null when the module's pom.xml is missing, unreadable, unparseable or has pom packaging. */
    public static Module extract(ConfigContext ctx, String dir, Map<String, LoadedPom> cache) {
        ScannedFile f = ctx.file(ConfigContext.join(dir, "pom.xml"));
        if (f == null) return null;
        LoadedPom lp = ParentChain.load(ctx, f, cache);
        if (lp == null || lp.pom().isPomPackaging()) return null;

        ParentChain chain = ParentChain.resolve(ctx, lp, cache);
        Map<String, String> bootLayer = chain.end() instanceof ParentChain.BootParent bp
                ? BootDefaults.properties(bp.version()) : null;
        PropertyLayers layers = new PropertyLayers(ctx, chain, bootLayer);

        List<Fact> facts = new ArrayList<>();
        Fact java = JavaVersionResolver.resolve(ctx, chain, layers, bootLayer != null, dir);
        Fact boot = BootVersion.fact(ctx, chain, layers, dir);
        if (boot != null) facts.add(boot);

        Map<String, FactEvidence> deps = dependencies(ctx, chain, layers, dir, facts);
        services(deps, dir, facts);
        boolean app = appModule(ctx, chain, layers, dir, facts);
        return new Module(dir, lp, app, java, facts, deps);
    }

    private static Map<String, FactEvidence> dependencies(ConfigContext ctx, ParentChain chain, PropertyLayers layers,
                                                          String dir, List<Fact> facts) {
        Map<String, FactEvidence> out = new TreeMap<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < chain.poms().size(); i++) {
            LoadedPom lp = chain.poms().get(i);
            for (Pom.Dep d : lp.pom().dependencies()) {
                if (seen.containsKey(d.ga())) continue; // nearest declaration wins
                seen.put(d.ga(), true);
                if ("test".equals(d.scope())) continue;
                if (d.groupId().contains("${") || d.artifactId().contains("${")) continue;
                FactEvidence ev = ctx.cite(lp.src(), i == 0 ? RULE_DEP : RULE_DEP_INHERITED, d.line());
                List<FactEvidence> evs = new ArrayList<>();
                evs.add(ev);
                String value = d.ga();
                if (d.version() != null) {
                    Resolved r = layers.resolve(d.version());
                    if (r.resolved()) {
                        value += ":" + r.value();
                        evs.addAll(r.evidence());
                    }
                }
                out.put(d.ga(), ev);
                facts.add(new Fact("dependency", value, FactStatus.KNOWN, Fact.VERIFIED, dir, null, null,
                        evs, null, null, null));
            }
        }
        return out;
    }

    private static void services(Map<String, FactEvidence> deps, String dir, List<Fact> facts) {
        Map<String, List<FactEvidence>> byService = new TreeMap<>();
        deps.forEach((ga, ev) -> {
            String s = ServiceTable.service(ga);
            if (s != null) {
                byService.computeIfAbsent(s, k -> new ArrayList<>())
                        .add(new FactEvidence(RULE_SERVICE, ev.path(), ev.line(), ev.commitSha(), ev.sha256()));
            }
        });
        byService.forEach((s, evs) -> facts.add(new Fact("service", s, FactStatus.KNOWN, Fact.VERIFIED, dir,
                null, null, evs, null, null, null)));
    }

    private static boolean appModule(ConfigContext ctx, ParentChain chain, PropertyLayers layers, String dir,
                                     List<Fact> facts) {
        LoadedPom module = chain.module();
        Pom.Plugin own = module.pom().plugin(ParentChain.BOOT_GROUP, BOOT_PLUGIN);
        LoadedPom declaring = null;
        Pom.Plugin plugin = null;
        String rule = null;
        if (own != null) {
            declaring = module;
            plugin = own;
            rule = RULE_PLUGIN_DECLARED;
        } else if ("jar".equals(module.pom().packaging())) {
            for (int i = 1; i < chain.poms().size() && plugin == null; i++) {
                Pom.Plugin p = chain.poms().get(i).pom().plugin(ParentChain.BOOT_GROUP, BOOT_PLUGIN);
                if (p != null) {
                    declaring = chain.poms().get(i);
                    plugin = p;
                    rule = RULE_PLUGIN_INHERITED;
                }
            }
        }
        if (plugin == null) return false;
        facts.add(new Fact("app.module", dir, FactStatus.KNOWN, Fact.VERIFIED, dir, null, null,
                List.of(ctx.cite(declaring.src(), rule, plugin.line())), null, null, null));

        // mainClass: nearest configuration from the module up to the declaring POM.
        for (LoadedPom lp : chain.poms()) {
            Pom.Plugin p = lp.pom().plugin(ParentChain.BOOT_GROUP, BOOT_PLUGIN);
            Pom.Val v = p == null ? null : p.config("mainClass");
            if (v != null) {
                FactEvidence ev = ctx.cite(lp.src(), RULE_MAIN_CLASS, v.line());
                Resolved r = layers.resolve(new PropertyLayers.Decl("mainClass", v.value(), ev, false));
                facts.add(r.resolved()
                        ? new Fact("entry.mainClass", r.value(), FactStatus.KNOWN, Fact.VERIFIED, dir, null, null,
                                r.evidence(), null, null, null)
                        : new Fact("entry.mainClass", null, FactStatus.UNRESOLVED, Fact.VERIFIED, dir, null, null,
                                r.evidence(), null, null, r.missing() != null
                                        ? "references property " + r.missing() : "property references are cyclic"));
                break;
            }
            if (lp == declaring) break;
        }
        return true;
    }
}

package com.proofix.runner.source;

import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * entry.mainClass from source: in each app module, the @SpringBootApplication classes that declare
 * public static void main(String[]). Merged with the spring-boot-maven-plugin mainClass fact:
 * the same class merges evidence, a different one is a CONFLICT. A POM value that could not be
 * resolved stays UNRESOLVED (the plugin setting decides which class runs), with the classes cited.
 */
public final class EntryPoints {
    private EntryPoints() {}

    public static final String NAME = "entry.mainClass";
    public static final String RULE = "spring-boot-application-annotation";
    static final String REASON_NONE = "no @SpringBootApplication class with main() found";
    static final String REASON_MULTIPLE = "multiple @SpringBootApplication classes";
    static final String REASON_DIFFERENT = "plugin mainClass and @SpringBootApplication class differ";

    /** Replaces or adds the entry.mainClass fact of every app module in {@code facts}. */
    public static void apply(List<JavaSources.Module> modules, JavaSources.Result sources, String commitSha,
                             List<FactEntry> facts) {
        for (JavaSources.Module m : modules) {
            if (!m.app()) continue;
            // class -> evidence, sorted by class name
            Map<String, List<FactEvidence>> found = new TreeMap<>();
            for (JavaSources.ParsedFile f : sources.files()) {
                if (!f.module().dir().equals(m.dir())) continue;
                for (SourceSummary.BootApp b : f.summary().bootApps()) {
                    if (!b.hasMain()) continue;
                    found.computeIfAbsent(b.canonicalName(), k -> new ArrayList<>())
                            .add(new FactEvidence(RULE, f.path(), b.annotationLine(), commitSha, f.sha256()));
                }
            }
            Fact pom = null;
            for (FactEntry e : facts) {
                if (e instanceof Fact f && f.name().equals(NAME) && f.module().equals(m.dir())) pom = f;
            }
            Fact merged = merge(m.dir(), pom, found);
            if (merged == pom) continue;
            if (pom != null) facts.remove(pom);
            facts.add(merged);
        }
    }

    static Fact merge(String dir, Fact pom, Map<String, List<FactEvidence>> found) {
        if (pom == null) {
            if (found.isEmpty()) return fact(dir, null, FactStatus.UNRESOLVED, List.of(), null, REASON_NONE);
            if (found.size() == 1) {
                Map.Entry<String, List<FactEvidence>> e = found.entrySet().iterator().next();
                return fact(dir, e.getKey(), FactStatus.KNOWN, e.getValue(), null, null);
            }
            return conflict(dir, found, REASON_MULTIPLE);
        }
        if (found.isEmpty()) return pom;
        if (pom.status() != FactStatus.KNOWN) {
            List<FactEvidence> ev = new ArrayList<>(pom.evidence());
            found.values().forEach(ev::addAll);
            return fact(dir, pom.value(), pom.status(), ev, pom.candidates(), pom.reason());
        }
        Map<String, List<FactEvidence>> all = new LinkedHashMap<>();
        found.forEach((k, v) -> all.put(k, new ArrayList<>(v)));
        all.computeIfAbsent(pom.value(), k -> new ArrayList<>()).addAll(pom.evidence());
        if (all.size() == 1) return fact(dir, pom.value(), FactStatus.KNOWN, all.get(pom.value()), null, null);
        return conflict(dir, new TreeMap<>(all), found.size() > 1 ? REASON_MULTIPLE : REASON_DIFFERENT);
    }

    private static Fact conflict(String dir, Map<String, List<FactEvidence>> byClass, String reason) {
        List<ValueWithEvidence> candidates = new ArrayList<>();
        List<FactEvidence> ev = new ArrayList<>();
        byClass.forEach((cls, e) -> {
            candidates.add(new ValueWithEvidence(cls, e));
            ev.addAll(e);
        });
        return fact(dir, null, FactStatus.CONFLICT, ev, candidates, reason);
    }

    private static Fact fact(String dir, String value, FactStatus status, List<FactEvidence> ev,
                             List<ValueWithEvidence> candidates, String reason) {
        return new Fact(NAME, value, status, Fact.VERIFIED, dir, null, null, ev, null, candidates, reason);
    }
}

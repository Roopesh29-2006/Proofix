package com.proofix.runner.source;

import com.proofix.runner.source.SourceSummary.Constant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * String constants and type names from every parsed source, and the evaluation of path
 * expressions against them. A name resolves only to a constant found in the scanned sources whose
 * initialiser is a literal or a '+' chain of literals and such constants; anything else (an
 * unknown or ambiguous name, a cycle, a method call) does not evaluate.
 */
public final class ConstantIndex {

    static final int MAX_DEPTH = 10;

    /** A constant with the file it was declared in (for its own imports and for evidence). */
    public record Entry(Constant constant, SourceSummary file, String path, String sha256) {}

    /** value null = not evaluable; used = constants the value depends on. */
    public record Eval(String value, List<Entry> used) {
        public boolean ok() {
            return value != null;
        }
    }

    /** Where a name is looked up: the file and the class scopes, innermost first. */
    public record Scope(SourceSummary file, List<String> classes) {}

    private final Map<String, List<Entry>> constants = new HashMap<>();
    private final Set<String> types = new HashSet<>();

    public void add(SourceSummary s, String path, String sha256) {
        types.addAll(s.types());
        for (Constant c : s.constants()) {
            constants.computeIfAbsent(c.owner() + "." + c.name(), k -> new ArrayList<>())
                    .add(new Entry(c, s, path, sha256));
        }
    }

    public Eval eval(Expr e, Scope scope) {
        List<Entry> used = new ArrayList<>();
        String v = eval(e, scope, used, new HashSet<>(), 0);
        return new Eval(v, v == null ? List.of() : List.copyOf(used));
    }

    private String eval(Expr e, Scope scope, List<Entry> used, Set<String> visiting, int depth) {
        if (depth > MAX_DEPTH) return null;
        if (e instanceof Expr.Lit l) return l.value();
        if (e instanceof Expr.Concat c) {
            StringBuilder b = new StringBuilder();
            for (Expr p : c.parts()) {
                String v = eval(p, scope, used, visiting, depth + 1);
                if (v == null) return null;
                b.append(v);
            }
            return b.toString();
        }
        if (e instanceof Expr.Name n) {
            Entry entry = lookup(n, scope);
            if (entry == null) return null;
            String key = entry.constant().owner() + "." + entry.constant().name();
            if (!visiting.add(key)) return null; // cycle
            String v = eval(entry.constant().init(), scopeOf(entry), used, visiting, depth + 1);
            visiting.remove(key);
            if (v == null) return null;
            if (!used.contains(entry)) used.add(entry);
            return v;
        }
        return null;
    }

    private Scope scopeOf(Entry e) {
        List<String> classes = new ArrayList<>();
        String owner = e.constant().owner();
        while (owner != null && types.contains(owner)) {
            classes.add(owner);
            int dot = owner.lastIndexOf('.');
            owner = dot < 0 ? null : owner.substring(0, dot);
        }
        return new Scope(e.file(), classes);
    }

    /** The single constant a name denotes, or null (unknown, ambiguous, or shadowed). */
    Entry lookup(Expr.Name n, Scope scope) {
        SourceSummary f = scope.file();
        if (n.qualifier() == null) {
            for (String c : scope.classes()) {
                if (constants.containsKey(c + "." + n.name())) return single(c, n.name());
            }
            String cls = f.staticImports().get(n.name());
            if (cls != null) return single(cls, n.name());
            List<String> owners = new ArrayList<>();
            for (String w : f.staticWildcards()) if (constants.containsKey(w + "." + n.name())) owners.add(w);
            return owners.size() == 1 ? single(owners.get(0), n.name()) : null;
        }
        String q = n.qualifier();
        int dot = q.indexOf('.');
        String first = dot < 0 ? q : q.substring(0, dot);
        String rest = dot < 0 ? "" : q.substring(dot);
        // Java's order: member types in scope, single-type imports, same package, on-demand imports,
        // and finally a fully qualified name.
        for (String c : scope.classes()) {
            if (types.contains(c + "." + first)) return single(c + "." + first + rest, n.name());
        }
        String imported = f.imports().get(first);
        if (imported != null) return types.contains(imported + rest) ? single(imported + rest, n.name()) : null;
        String pkg = f.packageName().isEmpty() ? first : f.packageName() + "." + first;
        if (types.contains(pkg)) return single(pkg + rest, n.name());
        List<String> owners = new ArrayList<>();
        for (String w : f.wildcardImports()) if (types.contains(w + "." + first)) owners.add(w + "." + first + rest);
        if (owners.size() > 1) return null;
        if (owners.size() == 1) return single(owners.get(0), n.name());
        return types.contains(q) ? single(q, n.name()) : null;
    }

    private Entry single(String owner, String name) {
        List<Entry> l = constants.get(owner + "." + name);
        return l != null && l.size() == 1 ? l.get(0) : null;
    }
}

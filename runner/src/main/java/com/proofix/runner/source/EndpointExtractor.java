package com.proofix.runner.source;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.facts.model.Endpoint;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.source.SourceSummary.Controller;
import com.proofix.runner.source.SourceSummary.Handler;
import com.proofix.runner.source.SourceSummary.Mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds endpoints from parsed controllers: context path + class path + method path, N paths x M
 * methods. Interface-declared and inherited mappings, WebFlux routes and JAX-RS are not covered
 * (warned). A path that is not a literal or resolvable constant is UNRESOLVED with its raw text.
 */
public final class EndpointExtractor {
    private EndpointExtractor() {}

    public static final String RULE_CLASS = "controller-class";
    public static final String RULE_HANDLER = "handler-mapping";
    public static final String RULE_CONSTANT = "path-constant";
    public static final String RULE_CONTEXT = "context-path";

    public static final String ANY = "ANY";
    static final Set<String> REQUEST_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    static final String REASON_PATH = "path is not a literal or resolvable constant";
    static final String REASON_AMBIGUOUS = "value and path are both set and differ";
    static final String REASON_METHOD = "request method not resolvable";
    static final String REASON_CONTEXT = "context path unknown";
    static final String NOT_COVERED_SUPERTYPE =
            "controller extends or implements a type and declares no mappings; inherited mappings are not listed";
    static final String NOT_COVERED_INTERFACE = "mapping annotations on an interface are not covered";
    static final String DUPLICATE_MESSAGE = "another handler is mapped to the same method and path";

    /** The servlet context path that applies to a module. */
    public sealed interface ContextPath permits None, Known, Unknown {}

    /** No context path is configured: no prefix. */
    public record None() implements ContextPath {}

    public record Known(String value, List<FactEvidence> evidence) implements ContextPath {}

    /** Configured but not a KNOWN value, or not attributable to one app: no prefix, with a reason. */
    public record Unknown() implements ContextPath {}

    public record Result(List<Endpoint> endpoints, boolean complete) {
        public Result {
            endpoints = List.copyOf(endpoints);
        }
    }

    public static Result extract(ConfigContext ctx, JavaSources.Result sources, Map<String, ContextPath> contextPaths) {
        boolean complete = sources.complete();
        String commit = ctx.scan().git().commitSha();
        List<Endpoint> raw = new ArrayList<>();
        for (JavaSources.ParsedFile f : sources.files()) {
            for (int line : f.summary().interfaceMappingLines()) {
                ctx.warn(new Warning(WarningCodes.ENDPOINTS_NOT_COVERED, null, f.path(), line, NOT_COVERED_INTERFACE));
                complete = false;
            }
            ContextPath cp = contextPaths.getOrDefault(f.module().dir(), new Unknown());
            for (Controller c : f.summary().controllers()) {
                if (c.handlers().isEmpty() && c.hasSupertype()) {
                    ctx.warn(new Warning(WarningCodes.ENDPOINTS_NOT_COVERED, null, f.path(), c.annotationLine(),
                            NOT_COVERED_SUPERTYPE));
                    complete = false;
                }
                ConstantIndex.Scope scope = new ConstantIndex.Scope(f.summary(), c.scopes());
                for (Handler h : c.handlers()) controllerEndpoints(sources.constants(), f, c, h, scope, cp, commit, raw);
            }
        }
        List<Endpoint> unique = dedupe(raw);
        duplicates(ctx, unique);
        return new Result(unique, complete);
    }

    private static void controllerEndpoints(ConstantIndex idx, JavaSources.ParsedFile f, Controller c, Handler h,
                                            ConstantIndex.Scope scope, ContextPath cp, String commit, List<Endpoint> out) {
        Mapping cm = c.classMapping();
        Mapping mm = h.mapping();
        List<Expr> classPaths = cm == null || cm.paths().isEmpty() ? List.of(new Expr.Lit("")) : cm.paths();
        List<Expr> methodPaths = mm.paths().isEmpty() ? List.of(new Expr.Lit("")) : mm.paths();

        // Spring combines class- and method-level request methods as a union; none means any.
        Set<String> methods = new TreeSet<>();
        boolean methodUnresolved = false;
        if (mm.fixedMethod() != null) methods.add(mm.fixedMethod());
        List<Expr> exprs = new ArrayList<>(mm.methods());
        if (cm != null) exprs.addAll(cm.methods());
        for (Expr e : exprs) {
            String m = requestMethod(e);
            if (m == null) methodUnresolved = true;
            else methods.add(m);
        }
        if (methodUnresolved || methods.isEmpty()) methods = Set.of(ANY);
        boolean ambiguous = mm.pathAmbiguous() || (cm != null && cm.pathAmbiguous());

        String handler = c.canonicalName() + "." + h.method();
        String module = f.module().dir();
        FactEvidence classEv = new FactEvidence(RULE_CLASS, f.path(), cm != null ? cm.line() : c.annotationLine(),
                commit, f.sha256());
        FactEvidence methodEv = new FactEvidence(RULE_HANDLER, f.path(), mm.line(), commit, f.sha256());

        for (Expr cpExpr : classPaths) {
            for (Expr mpExpr : methodPaths) {
                List<FactEvidence> ev = new ArrayList<>(List.of(classEv, methodEv));
                ConstantIndex.Eval ce = idx.eval(cpExpr, scope);
                ConstantIndex.Eval me = idx.eval(mpExpr, scope);
                String path = null;
                String rawPath = null;
                List<String> reasons = new ArrayList<>();
                FactStatus status = FactStatus.KNOWN;
                if (ambiguous) {
                    status = FactStatus.UNRESOLVED;
                    reasons.add(REASON_AMBIGUOUS);
                } else if (!ce.ok() || !me.ok()) {
                    status = FactStatus.UNRESOLVED;
                    reasons.add(REASON_PATH);
                    rawPath = cut(text(!ce.ok() ? cpExpr : mpExpr));
                } else {
                    for (ConstantIndex.Entry e : ce.used()) ev.add(cite(e, commit));
                    for (ConstantIndex.Entry e : me.used()) ev.add(cite(e, commit));
                    List<String> parts = new ArrayList<>();
                    if (cp instanceof Known k) {
                        parts.add(k.value());
                        for (FactEvidence x : k.evidence()) {
                            ev.add(new FactEvidence(RULE_CONTEXT, x.path(), x.line(), x.commitSha(), x.sha256()));
                        }
                    } else if (cp instanceof Unknown) {
                        reasons.add(REASON_CONTEXT);
                    }
                    parts.add(ce.value());
                    parts.add(me.value());
                    path = PathJoin.join(parts);
                }
                if (methodUnresolved) {
                    status = FactStatus.UNRESOLVED;
                    reasons.add(REASON_METHOD);
                }
                for (String m : methods) {
                    out.add(new Endpoint(m, path, path == null ? List.of() : PathJoin.variables(path), handler, module,
                            status, reasons.isEmpty() ? null : String.join("; ", reasons), rawPath, ev));
                }
            }
        }
    }

    private static FactEvidence cite(ConstantIndex.Entry e, String commit) {
        return new FactEvidence(RULE_CONSTANT, e.path(), e.constant().line(), commit, e.sha256());
    }

    /** GET, RequestMethod.GET or ...bind.annotation.RequestMethod.GET; anything else is null. */
    static String requestMethod(Expr e) {
        if (!(e instanceof Expr.Name n) || !REQUEST_METHODS.contains(n.name())) return null;
        String q = n.qualifier();
        return q == null || q.equals("RequestMethod") || q.endsWith(".RequestMethod") ? n.name() : null;
    }

    static String text(Expr e) {
        if (e instanceof Expr.Lit l) return "\"" + l.value() + "\"";
        if (e instanceof Expr.Name n) return n.text();
        if (e instanceof Expr.Other o) return o.text();
        List<String> parts = new ArrayList<>();
        for (Expr p : ((Expr.Concat) e).parts()) parts.add(text(p));
        return String.join(" + ", parts);
    }

    private static String cut(String s) {
        String c = Expr.clean(s);
        return c.length() > Endpoint.MAX_RAW_PATH ? c.substring(0, Endpoint.MAX_RAW_PATH) : c;
    }

    /** Merges endpoints with the same method, path, handler (and raw path), uniting their evidence. */
    private static List<Endpoint> dedupe(List<Endpoint> raw) {
        Map<String, Endpoint> byKey = new LinkedHashMap<>();
        for (Endpoint e : raw) {
            String key = e.method() + "\u0000" + e.path() + "\u0000" + e.handler() + "\u0000" + e.rawPath();
            Endpoint prev = byKey.get(key);
            if (prev == null) {
                byKey.put(key, e);
                continue;
            }
            List<FactEvidence> ev = new ArrayList<>(prev.evidence());
            for (FactEvidence x : e.evidence()) if (!ev.contains(x)) ev.add(x);
            boolean unresolved = prev.status() == FactStatus.UNRESOLVED || e.status() == FactStatus.UNRESOLVED;
            byKey.put(key, new Endpoint(prev.method(), prev.path(), prev.pathVariables(), prev.handler(), prev.module(),
                    unresolved ? FactStatus.UNRESOLVED : FactStatus.KNOWN,
                    prev.reason() != null ? prev.reason() : e.reason(), prev.rawPath(), ev));
        }
        return byKey.values().stream().sorted(Endpoint.ORDER).toList();
    }

    /** Same method and known path, different handlers: kept, and every handler after the first warned. */
    private static void duplicates(ConfigContext ctx, List<Endpoint> endpoints) {
        Map<String, List<Endpoint>> groups = new TreeMap<>();
        for (Endpoint e : endpoints) {
            if (e.path() != null) groups.computeIfAbsent(e.method() + " " + e.path(), k -> new ArrayList<>()).add(e);
        }
        groups.forEach((key, list) -> {
            Set<String> handlers = new TreeSet<>();
            list.forEach(e -> handlers.add(e.handler()));
            if (handlers.size() < 2) return;
            String first = handlers.iterator().next();
            for (Endpoint e : list) {
                if (e.handler().equals(first)) continue;
                FactEvidence at = e.evidence().stream().filter(x -> x.rule().equals(RULE_HANDLER)).findFirst().orElse(null);
                ctx.warn(new Warning(WarningCodes.DUPLICATE_ENDPOINT, key, at != null ? at.path() : null,
                        at != null ? at.line() : null, DUPLICATE_MESSAGE));
            }
        });
    }
}

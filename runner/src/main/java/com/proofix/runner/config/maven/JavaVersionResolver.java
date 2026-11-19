package com.proofix.runner.config.maven;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.config.maven.PropertyLayers.Decl;
import com.proofix.runner.config.maven.PropertyLayers.Resolved;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out the java.version fact of one module. Precedence, highest first:
 * <ol>
 *   <li>maven-compiler-plugin {@code <configuration>} release / source / target, from build/plugins
 *       of the module or a local parent (nearest declaration of each parameter wins);</li>
 *   <li>property maven.compiler.release;</li>
 *   <li>properties maven.compiler.source / target;</li>
 *   <li>property java.version.</li>
 * </ol>
 * A release (plugin or property) beats any source/target, as in the compiler itself. Every losing
 * declaration goes into "overridden" in this order. Properties merge child first, then local
 * parents, then the verified Boot parent layer.
 */
public final class JavaVersionResolver {
    private JavaVersionResolver() {}

    public static final String NAME = "java.version";
    public static final String COMPILER_PLUGIN = "maven-compiler-plugin";
    public static final String RULE_PLUGIN = "compiler-plugin-config";
    public static final String RULE_PLUGIN_INHERITED = "compiler-plugin-config-inherited";
    public static final String RULE_UNREAD_PARENT = "parent-not-read";

    private static final Pattern LEGACY = Pattern.compile("^1\\.(\\d+)$");
    private static final Pattern MAJOR = Pattern.compile("^\\d+$");

    /** "1.8" -> "8", "21" -> "21"; anything else -> null. */
    public static String normalize(String v) {
        if (v == null) return null;
        String t = v.trim();
        Matcher m = LEGACY.matcher(t);
        if (m.matches()) return m.group(1);
        return MAJOR.matcher(t).matches() ? t : null;
    }

    public static Fact resolve(ConfigContext ctx, ParentChain chain, PropertyLayers layers,
                               boolean bootLayerKnown, String module) {
        List<Decl> pRel = plugin(ctx, chain, "release");
        List<Decl> pSrc = plugin(ctx, chain, "source");
        List<Decl> pTgt = plugin(ctx, chain, "target");
        List<Decl> rel = layers.all("maven.compiler.release");
        List<Decl> src = layers.all("maven.compiler.source");
        List<Decl> tgt = layers.all("maven.compiler.target");
        List<Decl> jv = layers.all("java.version");

        List<Decl> order = new ArrayList<>();
        order.addAll(pRel);
        order.addAll(rel);
        order.addAll(pSrc);
        order.addAll(pTgt);
        order.addAll(src);
        order.addAll(tgt);
        order.addAll(jv);

        String unread = unreadReason(chain, bootLayerKnown);
        List<FactEvidence> parentEv = parentEvidence(ctx, chain, unread);

        Decl wRel = first(pRel, rel);
        Decl wSrc = first(pSrc, src);
        Decl wTgt = first(pTgt, tgt);

        if (wRel != null) {
            // A release is decisive even when the parent was not read: it beats every key a parent could set,
            // except a parent's own plugin <release>, which a child declaration also overrides.
            return single(module, layers.resolve(wRel), order, layers, unread, true, parentEv, chain);
        }
        if (wSrc != null || wTgt != null) {
            if (unread != null) return unresolved(module, unread + "; may set a higher-precedence key", parentEv,
                    resolvedEvidence(layers, wSrc, wTgt));
            Resolved rs = wSrc == null ? null : layers.resolve(wSrc);
            Resolved rt = wTgt == null ? null : layers.resolve(wTgt);
            if (rs != null && rt != null && rs.resolved() && rt.resolved()) {
                String ns = normalize(rs.value());
                String nt = normalize(rt.value());
                if (ns != null && nt != null && !ns.equals(nt)) {
                    Set<Decl> used = new HashSet<>(rs.used());
                    used.addAll(rt.used());
                    List<FactEvidence> ev = new ArrayList<>(rs.evidence());
                    ev.addAll(rt.evidence());
                    return new Fact(NAME, null, FactStatus.CONFLICT, Fact.VERIFIED, module, null, null, ev,
                            overridden(order, used, layers),
                            List.of(new ValueWithEvidence(ns, rs.evidence()), new ValueWithEvidence(nt, rt.evidence())),
                            "maven.compiler source and target disagree");
                }
            }
            Resolved w = rs != null && rs.resolved() ? rs : rt != null && rt.resolved() ? rt : (rs != null ? rs : rt);
            Set<Decl> extraUsed = new HashSet<>();
            List<FactEvidence> extraEv = new ArrayList<>();
            Resolved other = w == rs ? rt : rs;
            if (other != null && other.resolved() && normalize(other.value()) != null
                    && normalize(other.value()).equals(normalize(w.value()))) {
                extraUsed.addAll(other.used());
                extraEv.addAll(other.evidence());
            }
            return single(module, w, order, layers, null, false, extraEv, chain, extraUsed);
        }
        if (!jv.isEmpty()) {
            if (unread != null) return unresolved(module, unread + "; may set a higher-precedence key", parentEv,
                    layers.resolve(jv.get(0)).evidence());
            return single(module, layers.resolve(jv.get(0)), order, layers, null, false, List.of(), chain);
        }
        if (unread != null) return unresolved(module, unread, parentEv, List.of());
        return unresolved(module, "no Java version declared", List.of(), List.of());
    }

    private static Fact single(String module, Resolved w, List<Decl> order, PropertyLayers layers, String unread,
                               boolean decisive, List<FactEvidence> extraEv, ParentChain chain) {
        return single(module, w, order, layers, unread, decisive, extraEv, chain, Set.of());
    }

    private static Fact single(String module, Resolved w, List<Decl> order, PropertyLayers layers, String unread,
                               boolean decisive, List<FactEvidence> extraEv, ParentChain chain, Set<Decl> extraUsed) {
        List<FactEvidence> ev = new ArrayList<>(w.evidence());
        Set<Decl> used = new HashSet<>(w.used());
        used.addAll(extraUsed);
        if (!w.resolved()) {
            String why = w.missing() != null ? "references property " + w.missing() : "property references are cyclic or too deep";
            if (unread != null && w.missing() != null) why += " (" + unread + ")";
            if (decisive || unread == null) ev.addAll(extraEv);
            return new Fact(NAME, null, FactStatus.UNRESOLVED, Fact.VERIFIED, module, null, null, ev,
                    overridden(order, used, layers), null, why);
        }
        String v = normalize(w.value());
        if (v == null) {
            return new Fact(NAME, null, FactStatus.UNRESOLVED, Fact.VERIFIED, module, null, null, ev,
                    overridden(order, used, layers), null, "not a Java version number");
        }
        if (!decisive) ev.addAll(extraEv);
        boolean defaulted = w.onlySynthetic();
        String reason = null;
        if (defaulted && chain.end() instanceof ParentChain.BootParent bp) {
            reason = "default of spring-boot-starter-parent " + bp.version();
        }
        return new Fact(NAME, v, defaulted ? FactStatus.DEFAULTED : FactStatus.KNOWN, Fact.VERIFIED, module, null,
                null, ev, overridden(order, used, layers), null, reason);
    }

    private static Fact unresolved(String module, String reason, List<FactEvidence> parentEv, List<FactEvidence> local) {
        List<FactEvidence> ev = new ArrayList<>(local);
        ev.addAll(parentEv);
        return new Fact(NAME, null, FactStatus.UNRESOLVED, Fact.VERIFIED, module, null, null, ev, null, null, reason);
    }

    private static List<FactEvidence> resolvedEvidence(PropertyLayers layers, Decl a, Decl b) {
        List<FactEvidence> ev = new ArrayList<>();
        if (a != null) ev.addAll(layers.resolve(a).evidence());
        if (b != null) ev.addAll(layers.resolve(b).evidence());
        return ev;
    }

    private static List<ValueWithEvidence> overridden(List<Decl> order, Set<Decl> used, PropertyLayers layers) {
        List<ValueWithEvidence> out = new ArrayList<>();
        for (Decl d : order) {
            if (used.contains(d)) continue;
            Resolved r = layers.resolve(d);
            out.add(new ValueWithEvidence(r.resolved() ? normalizeOrRaw(r.value()) : null, r.evidence()));
        }
        return out;
    }

    private static String normalizeOrRaw(String v) {
        String n = normalize(v);
        return n != null ? n : v;
    }

    private static Decl first(List<Decl> a, List<Decl> b) {
        if (!a.isEmpty()) return a.get(0);
        return b.isEmpty() ? null : b.get(0);
    }

    private static String unreadReason(ParentChain chain, boolean bootLayerKnown) {
        if (chain.end() instanceof ParentChain.Unread u) return u.reason();
        if (chain.end() instanceof ParentChain.BootParent bp && !bootLayerKnown) {
            return "spring-boot-starter-parent " + bp.version() + ": Java default not verified for this version";
        }
        return null;
    }

    private static List<FactEvidence> parentEvidence(ConfigContext ctx, ParentChain chain, String unread) {
        if (unread == null) return List.of();
        LoadedPom at = chain.end() instanceof ParentChain.Unread u ? u.at()
                : ((ParentChain.BootParent) chain.end()).at();
        return List.of(ctx.cite(at.src(), RULE_UNREAD_PARENT, at.pom().parent().line()));
    }

    /** Each declaration of one compiler-plugin parameter in build/plugins, module first. */
    private static List<Decl> plugin(ConfigContext ctx, ParentChain chain, String param) {
        List<Decl> out = new ArrayList<>();
        for (int i = 0; i < chain.poms().size(); i++) {
            LoadedPom lp = chain.poms().get(i);
            Pom.Plugin p = lp.pom().plugin(Pom.DEFAULT_PLUGIN_GROUP, COMPILER_PLUGIN);
            if (p == null) continue;
            Pom.Val v = p.config(param);
            if (v == null) continue;
            out.add(new Decl(COMPILER_PLUGIN + "/" + param, v.value(),
                    ctx.cite(lp.src(), i == 0 ? RULE_PLUGIN : RULE_PLUGIN_INHERITED, v.line()), false));
        }
        return out;
    }
}

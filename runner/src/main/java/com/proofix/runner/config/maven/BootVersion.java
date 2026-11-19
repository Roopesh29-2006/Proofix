package com.proofix.runner.config.maven;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.config.maven.PropertyLayers.Resolved;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * spring.boot.version from the spring-boot-starter-parent {@code <version>} and/or an imported
 * spring-boot-dependencies BOM (type pom, scope import) in the module or a local parent. When the
 * two disagree the fact is UNRESOLVED and both are cited.
 */
public final class BootVersion {
    private BootVersion() {}

    public static final String NAME = "spring.boot.version";
    public static final String BOM = "spring-boot-dependencies";
    public static final String RULE_PARENT = "boot-starter-parent";
    public static final String RULE_BOM = "boot-bom-import";

    /** null when neither source exists. */
    public static Fact fact(ConfigContext ctx, ParentChain chain, PropertyLayers layers, String module) {
        String parentV = null;
        FactEvidence parentEv = null;
        if (chain.end() instanceof ParentChain.BootParent bp) {
            parentV = bp.version();
            parentEv = ctx.cite(bp.at().src(), RULE_PARENT, bp.at().pom().parent().line());
        }

        Resolved bom = null;
        for (LoadedPom lp : chain.poms()) {
            for (Pom.Dep d : lp.pom().managedDependencies()) {
                if (d.groupId().equals(ParentChain.BOOT_GROUP) && d.artifactId().equals(BOM)
                        && "import".equals(d.scope()) && "pom".equals(d.type())) {
                    FactEvidence ev = ctx.cite(lp.src(), RULE_BOM, d.line());
                    bom = layers.resolve(new PropertyLayers.Decl(BOM, d.version(), ev, false));
                    break;
                }
            }
            if (bom != null) break;
        }

        if (parentEv == null && bom == null) return null;
        List<FactEvidence> ev = new ArrayList<>();
        if (parentEv != null) ev.add(parentEv);
        if (bom != null) ev.addAll(bom.evidence());

        if (bom != null && !bom.resolved()) {
            String why = bom.missing() != null ? "BOM version references property " + bom.missing()
                    : "BOM version is missing or its references are cyclic";
            return unresolved(module, ev, why);
        }
        if (parentEv != null && parentV == null) return unresolved(module, ev, "spring-boot-starter-parent has no version");
        if (parentEv != null && bom != null && !parentV.equals(bom.value())) {
            return unresolved(module, ev, "spring-boot-starter-parent and BOM import disagree");
        }
        String v = parentV != null ? parentV : bom.value();
        return new Fact(NAME, v, FactStatus.KNOWN, Fact.VERIFIED, module, null, null, ev, null, null, null);
    }

    private static Fact unresolved(String module, List<FactEvidence> ev, String reason) {
        return new Fact(NAME, null, FactStatus.UNRESOLVED, Fact.VERIFIED, module, null, null, ev, null, null, reason);
    }
}

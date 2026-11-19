package com.proofix.runner.config.jdk;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;
import com.proofix.runner.scan.model.ScannedFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Compares an app module's compile level (the java.version fact) with the runtime JDK of the
 * nearest Dockerfile and the nearest .java-version (module dir, then stack root, then repo root):
 * <ul>
 *   <li>runtime major &lt; compile major: CONFLICT, reason "runtime older than compile level";</li>
 *   <li>runtime major &gt;= compile major: unchanged, file cited with rule jdk-runtime-compatible;</li>
 *   <li>java.version not KNOWN/DEFAULTED: unchanged (a runtime never fills it), file cited with
 *       rule jdk-runtime-unverified.</li>
 * </ul>
 */
public final class JavaVersionConflict {
    private JavaVersionConflict() {}

    public static final String RULE_COMPATIBLE = "jdk-runtime-compatible";
    public static final String RULE_OLDER = "jdk-runtime-older";
    public static final String RULE_UNVERIFIED = "jdk-runtime-unverified";
    public static final String OLDER_REASON = "runtime older than compile level";

    private record Found(String major, FactEvidence evidence) {}

    public static Fact apply(ConfigContext ctx, Fact java, String moduleDir, String stackRoot) {
        Set<String> dirs = new LinkedHashSet<>(List.of(moduleDir, stackRoot, "."));
        List<Found> runtimes = new ArrayList<>();
        find(ctx, dirs, "Dockerfile", DockerfileJdk::parse, runtimes);
        find(ctx, dirs, ".java-version", JavaVersionFile::parse, runtimes);
        if (runtimes.isEmpty()) return java;

        boolean verified = (java.status() == FactStatus.KNOWN || java.status() == FactStatus.DEFAULTED)
                && java.value() != null && java.value().matches("\\d+");
        List<FactEvidence> ev = new ArrayList<>(java.evidence());
        if (!verified) {
            for (Found f : runtimes) ev.add(withRule(f.evidence(), RULE_UNVERIFIED));
            return copy(java, java.value(), java.status(), ev, java.candidates(), java.reason());
        }
        int compile = Integer.parseInt(java.value());
        List<ValueWithEvidence> older = new ArrayList<>();
        for (Found f : runtimes) {
            if (Integer.parseInt(f.major()) < compile) {
                FactEvidence e = withRule(f.evidence(), RULE_OLDER);
                ev.add(e);
                older.add(new ValueWithEvidence(f.major(), List.of(e)));
            } else {
                ev.add(withRule(f.evidence(), RULE_COMPATIBLE));
            }
        }
        if (older.isEmpty()) return copy(java, java.value(), java.status(), ev, java.candidates(), java.reason());
        List<ValueWithEvidence> candidates = new ArrayList<>();
        candidates.add(new ValueWithEvidence(java.value(), java.evidence()));
        candidates.addAll(older);
        return copy(java, null, FactStatus.CONFLICT, ev, candidates, OLDER_REASON);
    }

    private static void find(ConfigContext ctx, Set<String> dirs, String name,
                             Function<byte[], DockerfileJdk.Runtime> parser, List<Found> out) {
        for (String d : dirs) {
            ScannedFile f = ctx.file(ConfigContext.join(d, name));
            if (f == null) continue;
            ConfigContext.Source src = ctx.read(f);
            if (src == null) return;
            DockerfileJdk.Runtime r = parser.apply(src.data().bytes());
            if (r != null) out.add(new Found(r.major(), ctx.cite(src, RULE_COMPATIBLE, r.line())));
            return; // nearest file only, recognised or not
        }
    }

    private static FactEvidence withRule(FactEvidence e, String rule) {
        return new FactEvidence(rule, e.path(), e.line(), e.commitSha(), e.sha256());
    }

    private static Fact copy(Fact f, String value, FactStatus status, List<FactEvidence> ev,
                             List<ValueWithEvidence> candidates, String reason) {
        return new Fact(f.name(), value, status, f.label(), f.module(), f.profile(), f.envVar(), ev,
                f.overridden(), candidates, reason);
    }
}

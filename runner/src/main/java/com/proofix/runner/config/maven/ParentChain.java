package com.proofix.runner.config.maven;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The local parent chain of a module POM, module first, and how it ends. Parents are looked up
 * only among scanned files. Like Maven, a local candidate counts only when its groupId:artifactId
 * matches the {@code <parent>} element; otherwise the parent is remote.
 */
public record ParentChain(List<LoadedPom> poms, End end) {

    public static final int MAX_HOPS = 10;
    public static final String BOOT_GROUP = "org.springframework.boot";
    public static final String BOOT_PARENT = "spring-boot-starter-parent";

    public ParentChain {
        poms = List.copyOf(poms);
    }

    public LoadedPom module() {
        return poms.get(0);
    }

    /** How the chain ends. {@code at} is the POM whose {@code <parent>} element is cited. */
    public sealed interface End permits NoParent, BootParent, Unread {}

    public record NoParent() implements End {}

    public record BootParent(String version, LoadedPom at) implements End {}

    /** A parent we did not read; reason says why. It may set any property. */
    public record Unread(String reason, LoadedPom at) implements End {}

    public static final String REMOTE = "remote parent not read";
    public static final String OUTSIDE = "parent POM is outside the repository; not read";
    public static final String UNPARSEABLE = "parent POM could not be parsed";
    public static final String TOO_LONG = "parent chain longer than " + MAX_HOPS + " or cyclic; not followed";

    public static ParentChain resolve(ConfigContext ctx, LoadedPom module, Map<String, LoadedPom> cache) {
        List<LoadedPom> poms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        LoadedPom cur = module;
        while (true) {
            poms.add(cur);
            seen.add(cur.path());
            Pom.Parent p = cur.pom().parent();
            if (p == null) return new ParentChain(poms, new NoParent());
            if (p.groupId() != null && p.artifactId() != null && p.is(BOOT_GROUP, BOOT_PARENT)) {
                return new ParentChain(poms, new BootParent(p.version(), cur));
            }
            if (poms.size() > MAX_HOPS) return new ParentChain(poms, new Unread(TOO_LONG, cur));
            if ("".equals(p.relativePath())) return new ParentChain(poms, new Unread(REMOTE, cur));

            String rel = p.relativePath() == null ? "../pom.xml" : p.relativePath();
            String target = ConfigContext.normalize(ConfigContext.join(cur.dir(), rel));
            if (target == null) {
                ctx.warn(new Warning(WarningCodes.PARENT_OUTSIDE_REPO, cur.path(),
                        "parent relativePath leaves the repository; not followed"));
                return new ParentChain(poms, new Unread(OUTSIDE, cur));
            }
            ScannedFile f = ctx.file(target);
            if (f == null || !f.fileName().equals("pom.xml")) f = ctx.file(ConfigContext.join(target, "pom.xml"));
            if (f == null) {
                if (p.relativePath() != null) {
                    ctx.warn(new Warning(WarningCodes.PARENT_NOT_FOUND, cur.path(),
                            "parent relativePath does not point to a scanned pom.xml"));
                }
                return new ParentChain(poms, new Unread(REMOTE, cur));
            }
            if (seen.contains(f.path())) return new ParentChain(poms, new Unread(TOO_LONG, cur));
            LoadedPom parent = load(ctx, f, cache);
            if (parent == null) return new ParentChain(poms, new Unread(UNPARSEABLE, cur));
            Pom pp = parent.pom();
            if (pp.groupId() == null || pp.artifactId() == null || !p.is(pp.groupId(), pp.artifactId())) {
                return new ParentChain(poms, new Unread(REMOTE, cur));
            }
            cur = parent;
        }
    }

    /** Reads and parses a POM once per run; null (with a warning) when unreadable or unparseable. */
    public static LoadedPom load(ConfigContext ctx, ScannedFile f, Map<String, LoadedPom> cache) {
        if (cache.containsKey(f.path())) return cache.get(f.path());
        LoadedPom out = null;
        ConfigContext.Source src = ctx.read(f);
        if (src != null) {
            try {
                out = new LoadedPom(src, PomParser.parse(src.data().bytes()));
            } catch (PomParser.PomException e) {
                ctx.warn(new Warning(WarningCodes.MARKER_UNPARSEABLE, f.path(), "marker file could not be parsed"));
            }
        }
        cache.put(f.path(), out);
        return out;
    }
}

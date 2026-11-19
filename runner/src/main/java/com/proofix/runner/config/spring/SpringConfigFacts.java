package com.proofix.runner.config.spring;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.config.maven.MavenFacts;
import com.proofix.runner.detect.RuleTable;
import com.proofix.runner.facts.model.EnvVarFact;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.ValueWithEvidence;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring config facts for one app module, from application[-profile].{properties,yml,yaml} in its
 * src/main/resources. Within a profile, .properties beats .yml beats .yaml (Spring's order) and a
 * later document beats an earlier one; losers go into "overridden". Only explicit ${NAME}
 * placeholders become env vars; no relaxed binding is inferred.
 */
public final class SpringConfigFacts {
    private SpringConfigFacts() {}

    public static final String SERVER_PORT = "server.port";
    public static final String CONTEXT_PATH = "server.servlet.context-path";
    public static final String DATASOURCE_URL = "spring.datasource.url";
    public static final String DATASOURCE_KIND = "spring.datasource.kind";
    public static final String BASE_PATH = "management.endpoints.web.base-path";
    public static final String MGMT_PORT = "management.server.port";
    public static final String MGMT_BASE_PATH = "management.server.base-path";
    public static final String HEALTH_PATH = "health.path";
    public static final String HEALTH_EFFECTIVE = "health.effectivePath";

    public static final List<String> PLAIN_KEYS = List.of(SERVER_PORT, CONTEXT_PATH, BASE_PATH, MGMT_PORT, MGMT_BASE_PATH);
    public static final String ACTUATOR = "org.springframework.boot:spring-boot-starter-actuator";
    public static final String DEFAULT_BASE_PATH = "/actuator";

    public static final String RULE_CONFIG = "spring-config";
    public static final String RULE_ENV = "env-placeholder";
    public static final String RULE_ACTUATOR = "actuator-dependency";
    public static final String RULE_DS_KIND = "datasource-url-scheme";

    private static final Pattern URL = Pattern.compile(
            "^jdbc:([a-z0-9]+)://(?:[^@/?;#]*@)?([A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:.]+])(?::(\\d{1,5}))?/([A-Za-z0-9_.\\-]+)(?:[?;].*)?$");
    private static final Pattern H2_MEM = Pattern.compile("^jdbc:h2:mem:([A-Za-z0-9_\\-]+)(?:;.*)?$");
    private static final Pattern SCHEME = Pattern.compile("^jdbc:([a-z0-9]+):");
    private static final Pattern USERINFO = Pattern.compile("^jdbc:[a-z0-9]+://([^@/?;#]*)@");
    private static final Pattern PASSWORD_PARAM = Pattern.compile("(?i)[?;&]password=([^&;]*)");

    /** A config entry with the file it came from; rank orders files, doc orders documents. */
    private record Located(ConfigEntry entry, ConfigContext.Source src, int rank, int doc) {}

    private static final Comparator<Located> WINNER_FIRST = Comparator
            .comparingInt(Located::rank).thenComparing(Comparator.comparingInt(Located::doc).reversed());

    /** Value derivation of one entry. */
    record Derived(String value, FactStatus status, String envVar, String reason) {}

    /** Accumulates env var uses across all modules. */
    public static final class EnvAcc {
        boolean required;
        boolean withheld;
        final Set<String> defaults = new TreeSet<>();
        final List<FactEvidence> evidence = new ArrayList<>();
    }

    public static void extract(ConfigContext ctx, MavenFacts.Module module, Map<String, EnvAcc> env, List<FactEntry> out) {
        String dir = module.dir();
        String resources = ConfigContext.join(dir, "src/main/resources");
        // profile -> key -> declarations, winner first
        Map<String, Map<String, List<Located>>> byProfile = new TreeMap<>();
        List<ScannedFile> files = ctx.scan().files().stream()
                .filter(f -> f.dir().equals(resources) && f.fileName().startsWith("application")
                        && RuleTable.isContentFile(f))
                .sorted(Comparator.comparing(ScannedFile::path)).toList();
        for (ScannedFile f : files) {
            ConfigContext.Source src = ctx.read(f);
            if (src == null) continue;
            String name = f.fileName();
            String profile = ConfigDocs.fileProfile(name);
            int rank;
            ConfigDocs docs;
            if (name.endsWith(".properties")) {
                rank = 0;
                docs = PropertiesConfigReader.read(f.path(), src.data().bytes(), profile);
            } else {
                rank = name.endsWith(".yml") ? 1 : 2;
                docs = YamlConfigReader.read(f.path(), src.data().bytes(), profile);
            }
            docs.warnings().forEach(ctx::warn);
            for (int i = 0; i < docs.docs().size(); i++) {
                ConfigDocs.Doc d = docs.docs().get(i);
                for (ConfigEntry e : d.entries().values()) {
                    byProfile.computeIfAbsent(d.profile(), p -> new TreeMap<>())
                            .computeIfAbsent(e.key(), k -> new ArrayList<>())
                            .add(new Located(e, src, rank, i));
                    recordEnv(ctx, e, src, env);
                }
            }
        }
        byProfile.values().forEach(m -> m.values().forEach(l -> l.sort(WINNER_FIRST)));

        for (Map.Entry<String, Map<String, List<Located>>> p : byProfile.entrySet()) {
            String profile = p.getKey();
            for (String key : PLAIN_KEYS) {
                List<Located> decls = p.getValue().get(key);
                if (decls != null) out.add(plainFact(ctx, dir, profile, key, decls));
            }
            List<Located> url = p.getValue().get(DATASOURCE_URL);
            if (url != null) datasource(ctx, dir, profile, url, out);
        }

        FactEvidence actuator = module.dependencies().get(ACTUATOR);
        if (actuator != null) health(ctx, dir, byProfile, actuator, out);
    }

    /** Env var facts for everything accumulated, sorted by name. */
    public static List<EnvVarFact> envFacts(Map<String, EnvAcc> env) {
        List<EnvVarFact> out = new ArrayList<>();
        new TreeMap<>(env).forEach((name, a) -> {
            String def = null;
            if (!a.required && !a.withheld && a.defaults.size() == 1) def = a.defaults.iterator().next();
            out.add(new EnvVarFact(EnvVarFact.PREFIX + name, a.required, def, a.evidence));
        });
        return out;
    }

    private static void recordEnv(ConfigContext ctx, ConfigEntry e, ConfigContext.Source src, Map<String, EnvAcc> env) {
        for (Placeholders.Placeholder p : e.placeholders()) {
            if (!p.envStyle()) continue;
            EnvAcc a = env.computeIfAbsent(p.name(), k -> new EnvAcc());
            a.evidence.add(ctx.cite(src, RULE_ENV, e.line()));
            if (!p.hasDefault()) {
                a.required = true;
            } else if (e.secretKey() || SecretKeys.isSecret(p.name()) || p.defaultValue() == null
                    || p.defaultValue().contains("${")) {
                a.withheld = true;
            } else {
                a.defaults.add(p.defaultValue());
            }
        }
    }

    static Derived derive(ConfigEntry e) {
        if (e.value() == null) return new Derived(null, FactStatus.UNRESOLVED, null, "secret value not read");
        Placeholders.Parsed parsed = e.parsed();
        List<Placeholders.Placeholder> refs = parsed.placeholders();
        if (refs.isEmpty()) return new Derived(e.value(), FactStatus.KNOWN, null, null);
        for (Placeholders.Placeholder p : refs) {
            if (!p.envStyle()) return new Derived(null, FactStatus.UNRESOLVED, null, "references property " + p.name());
        }
        String envVar = parsed.pure() ? refs.get(0).name() : null;
        for (Placeholders.Placeholder p : refs) {
            if (!p.hasDefault()) {
                return new Derived(null, FactStatus.UNRESOLVED, envVar, "set by environment variable " + p.name());
            }
            if (SecretKeys.isSecret(p.name()) || e.secretKey()) {
                return new Derived(null, FactStatus.UNRESOLVED, envVar, "default withheld: secret-looking name");
            }
        }
        String v = parsed.withDefaults();
        if (v == null) return new Derived(null, FactStatus.UNRESOLVED, envVar, "default references another placeholder");
        String reason = envVar != null
                ? "placeholder default; environment variable " + envVar + " may override"
                : "placeholder defaults; environment variables may override";
        return new Derived(v, FactStatus.DEFAULTED, envVar, reason);
    }

    private static Fact plainFact(ConfigContext ctx, String dir, String profile, String key, List<Located> decls) {
        Located w = decls.get(0);
        Derived d = derive(w.entry());
        List<ValueWithEvidence> overridden = new ArrayList<>();
        for (Located l : decls.subList(1, decls.size())) {
            overridden.add(new ValueWithEvidence(derive(l.entry()).value(), List.of(cite(ctx, l))));
        }
        return new Fact(key, d.value(), d.status(), Fact.VERIFIED, dir, profile, d.envVar(), List.of(cite(ctx, w)),
                overridden, null, d.reason());
    }

    private static FactEvidence cite(ConfigContext ctx, Located l) {
        return ctx.cite(l.src(), RULE_CONFIG, l.entry().line());
    }

    // --- datasource -----------------------------------------------------------------------

    private static void datasource(ConfigContext ctx, String dir, String profile, List<Located> decls, List<FactEntry> out) {
        for (Located l : decls) literalSecretInUrl(ctx, l);
        Located w = decls.get(0);
        Derived d = urlDerived(w.entry());
        List<ValueWithEvidence> overridden = new ArrayList<>();
        for (Located l : decls.subList(1, decls.size())) {
            overridden.add(new ValueWithEvidence(urlDerived(l.entry()).value(), List.of(cite(ctx, l))));
        }
        out.add(new Fact(DATASOURCE_URL, d.value(), d.status(), Fact.VERIFIED, dir, profile, d.envVar(),
                List.of(cite(ctx, w)), overridden, null, d.reason()));

        String raw = w.entry().value();
        Matcher m = raw == null ? null : SCHEME.matcher(raw);
        if (m != null && m.find()) {
            out.add(new Fact(DATASOURCE_KIND, m.group(1).toUpperCase(Locale.ROOT), FactStatus.KNOWN, Fact.VERIFIED,
                    dir, profile, null, List.of(ctx.cite(w.src(), RULE_DS_KIND, w.entry().line())), null, null, null));
        }
    }

    /** [SEC] The URL is rebuilt from a strict pattern; anything else is never echoed. */
    static Derived urlDerived(ConfigEntry e) {
        Derived d = derive(e);
        if (d.value() == null) return d;
        String rebuilt = rebuildUrl(d.value());
        if (rebuilt == null) {
            return new Derived(null, FactStatus.UNRESOLVED, d.envVar(), "datasource URL not in a recognised form");
        }
        return new Derived(rebuilt, d.status(), d.envVar(), d.reason());
    }

    static String rebuildUrl(String v) {
        Matcher m = URL.matcher(v.trim());
        if (m.matches()) {
            return "jdbc:" + m.group(1) + "://" + m.group(2) + (m.group(3) != null ? ":" + m.group(3) : "") + "/" + m.group(4);
        }
        Matcher h = H2_MEM.matcher(v.trim());
        if (h.matches()) return "jdbc:h2:mem:" + h.group(1);
        return null;
    }

    private static void literalSecretInUrl(ConfigContext ctx, Located l) {
        String raw = l.entry().value();
        if (raw == null) return;
        boolean literal = false;
        Matcher u = USERINFO.matcher(raw);
        if (u.find()) {
            String info = u.group(1);
            int colon = info.indexOf(':');
            if (colon >= 0 && isLiteral(info.substring(colon + 1))) literal = true;
        }
        Matcher p = PASSWORD_PARAM.matcher(raw);
        while (!literal && p.find()) {
            if (isLiteral(p.group(1))) literal = true;
        }
        if (literal) {
            ctx.warn(new Warning(WarningCodes.LITERAL_SECRET, DATASOURCE_URL, l.src().path(), l.entry().line(),
                    ConfigEntry.LITERAL_SECRET_MESSAGE));
        }
    }

    private static boolean isLiteral(String s) {
        return !s.isEmpty() && Placeholders.parse(s).hasLiteral();
    }

    // --- health -----------------------------------------------------------------------------

    private record Input(String key, Located decl, Derived derived) {
        boolean set() {
            return decl != null;
        }
    }

    private static void health(ConfigContext ctx, String dir, Map<String, Map<String, List<Located>>> byProfile,
                               FactEvidence actuatorDep, List<FactEntry> out) {
        List<String> keys = List.of(CONTEXT_PATH, BASE_PATH, MGMT_PORT, MGMT_BASE_PATH);
        Set<String> profiles = new TreeSet<>();
        profiles.add(ConfigDocs.DEFAULT_PROFILE);
        byProfile.forEach((p, m) -> {
            if (keys.stream().anyMatch(m::containsKey)) profiles.add(p);
        });
        FactEvidence dep = new FactEvidence(RULE_ACTUATOR, actuatorDep.path(), actuatorDep.line(),
                actuatorDep.commitSha(), actuatorDep.sha256());
        for (String profile : profiles) {
            Input ctxPath = input(byProfile, profile, CONTEXT_PATH);
            Input base = input(byProfile, profile, BASE_PATH);
            Input port = input(byProfile, profile, MGMT_PORT);
            Input mBase = input(byProfile, profile, MGMT_BASE_PATH);

            out.add(compose(ctx, dir, profile, HEALTH_PATH, dep, List.of(base)));
            List<Input> parts = port.set() ? List.of(mBase, base) : List.of(ctxPath, base);
            List<Input> cited = port.set() ? List.of(port, mBase, base) : List.of(ctxPath, base);
            out.add(compose(ctx, dir, profile, HEALTH_EFFECTIVE, dep, parts, cited));
        }
    }

    private static Input input(Map<String, Map<String, List<Located>>> byProfile, String profile, String key) {
        List<Located> l = byProfile.getOrDefault(profile, Map.of()).get(key);
        if (l == null && !profile.equals(ConfigDocs.DEFAULT_PROFILE)) {
            l = byProfile.getOrDefault(ConfigDocs.DEFAULT_PROFILE, Map.of()).get(key);
        }
        if (l == null) return new Input(key, null, null);
        return new Input(key, l.get(0), derive(l.get(0).entry()));
    }

    private static Fact compose(ConfigContext ctx, String dir, String profile, String name, FactEvidence dep,
                                List<Input> parts) {
        return compose(ctx, dir, profile, name, dep, parts, parts);
    }

    /** parts are joined in order, then "/health"; an unset base-path means Spring's default /actuator. */
    private static Fact compose(ConfigContext ctx, String dir, String profile, String name, FactEvidence dep,
                                List<Input> parts, List<Input> cited) {
        List<FactEvidence> ev = new ArrayList<>();
        ev.add(dep);
        for (Input i : cited) {
            if (i.set()) ev.add(cite(ctx, i.decl()));
        }
        StringBuilder path = new StringBuilder();
        List<String> defaulted = new ArrayList<>();
        for (Input i : cited) {
            if (i.set() && i.derived().status() == FactStatus.UNRESOLVED && parts.contains(i)) {
                return new Fact(name, null, FactStatus.UNRESOLVED, Fact.VERIFIED, dir, profile, null, ev, null, null,
                        i.key() + " unresolved: " + i.derived().reason());
            }
        }
        for (Input i : parts) {
            String seg;
            if (!i.set()) {
                if (i.key().equals(BASE_PATH)) {
                    seg = DEFAULT_BASE_PATH;
                    defaulted.add(BASE_PATH + " not set; Spring Boot default " + DEFAULT_BASE_PATH);
                } else {
                    seg = "";
                }
            } else {
                seg = i.derived().value();
                if (i.derived().status() == FactStatus.DEFAULTED) defaulted.add(i.key() + " from placeholder default");
            }
            path.append(segment(seg));
        }
        path.append("/health");
        FactStatus status = defaulted.isEmpty() ? FactStatus.KNOWN : FactStatus.DEFAULTED;
        return new Fact(name, path.toString(), status, Fact.VERIFIED, dir, profile, null, ev, null, null,
                defaulted.isEmpty() ? null : String.join("; ", defaulted));
    }

    /** "" and "/" add nothing; otherwise exactly one leading '/' and no trailing '/'. */
    static String segment(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        while (t.startsWith("/")) t = t.substring(1);
        return t.isEmpty() ? "" : "/" + t;
    }
}

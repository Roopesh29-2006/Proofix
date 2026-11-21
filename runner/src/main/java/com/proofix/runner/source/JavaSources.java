package com.proofix.runner.source;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.detect.RuleTable;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads and parses the Java sources of the backend Maven modules, one file at a time, in two
 * passes. Pass 1 parses files with controller/mapping tokens, SpringBootApplication, @Value or
 * getenv. Pass 2 parses only files that mention, as a whole word, a constant name a mapping path
 * references, and that may declare a String constant. Both passes share one parse budget.
 * Every read goes through {@link ConfigContext#read} (PathGuard'd scan entries, MarkerReader).
 */
public final class JavaSources {
    private JavaSources() {}

    public static final int MAX_PARSED = 2000;

    static final String LIMIT_MESSAGE = "too many Java sources to parse; the rest were skipped";
    static final String NOT_COVERED_TEXT = "WebFlux RouterFunction or JAX-RS @Path found; those endpoints are not listed";
    static final String NOT_COVERED_KOTLIN = "Kotlin sources are not parsed; their endpoints are not listed";

    /** A backend Maven module: its directory, its stack root and whether it is the app module. */
    public record Module(String dir, String stackPath, boolean app) {
        String sourceRoot() {
            return ConfigContext.join(dir, "src/main/java/");
        }
    }

    public record ParsedFile(String path, String sha256, Module module, SourceSummary summary) {}

    /** complete is false when a file that may hold endpoints was not read or parsed. */
    public record Result(List<ParsedFile> files, ConstantIndex constants, boolean complete) {
        public Result {
            files = List.copyOf(files);
        }
    }

    private static final class Run {
        final ConfigContext ctx;
        final int max;
        final SafeJavaParser parser = new SafeJavaParser();
        final List<ParsedFile> files = new ArrayList<>();
        final ConstantIndex index = new ConstantIndex();
        int parsed;
        boolean limitWarned;
        boolean complete = true;

        Run(ConfigContext ctx, int max) {
            this.ctx = ctx;
            this.max = max;
        }
    }

    public static Result read(ConfigContext ctx, List<Module> modules) {
        return read(ctx, modules, MAX_PARSED);
    }

    public static Result read(ConfigContext ctx, List<Module> modules, int maxParsed) {
        Run run = new Run(ctx, maxParsed);
        List<ScannedFile> all = ctx.scan().files().stream()
                .sorted(Comparator.comparing(ScannedFile::path)).toList();
        kotlin(run, modules, all);

        List<ScannedFile> later = new ArrayList<>();
        List<Module> laterModules = new ArrayList<>();
        for (ScannedFile f : all) {
            Module m = moduleOf(modules, f);
            if (m == null) continue;
            ConfigContext.Source src = ctx.read(f);
            if (src == null) { // too large or unreadable (MarkerReader warned)
                run.complete = false;
                continue;
            }
            String text = new String(src.data().bytes(), StandardCharsets.UTF_8);
            if (text.contains("RouterFunction") || text.contains("@Path(")) {
                ctx.warn(new Warning(WarningCodes.ENDPOINTS_NOT_COVERED, f.path(), NOT_COVERED_TEXT));
                run.complete = false;
            }
            boolean endpoints = endpointTokens(text);
            if (endpoints || otherTokens(text)) {
                parse(run, src, m, endpoints);
            } else {
                later.add(f);
                laterModules.add(m);
            }
        }

        Set<String> names = referencedNames(run.files);
        if (!names.isEmpty()) {
            Pattern word = Pattern.compile("\\b(?:" + names.stream().map(Pattern::quote)
                    .collect(Collectors.joining("|")) + ")\\b");
            for (int i = 0; i < later.size(); i++) {
                ConfigContext.Source src = ctx.read(later.get(i));
                if (src == null) continue;
                String text = new String(src.data().bytes(), StandardCharsets.UTF_8);
                if (mayDeclareConstant(text) && word.matcher(text).find()) parse(run, src, laterModules.get(i), false);
            }
        }
        return new Result(run.files, run.index, run.complete);
    }

    private static void parse(Run run, ConfigContext.Source src, Module m, boolean endpointCandidate) {
        if (run.parsed >= run.max) {
            if (!run.limitWarned) {
                run.ctx.warn(new Warning(WarningCodes.SOURCE_LIMIT_HIT, null, LIMIT_MESSAGE));
                run.limitWarned = true;
            }
            run.complete = false;
            return;
        }
        run.parsed++;
        SafeJavaParser.Outcome o = run.parser.parse(src.data().bytes());
        SourceSummary summary = null;
        Integer line = null;
        if (o instanceof SafeJavaParser.Parsed p) {
            try {
                summary = SourceSummarizer.summarize(p.unit());
            } catch (RuntimeException | StackOverflowError e) {
                summary = null; // e is ignored: its message can quote the source
            }
        } else if (o instanceof SafeJavaParser.Failed fl) {
            line = fl.line();
        }
        if (summary == null) {
            run.ctx.warn(new Warning(WarningCodes.SOURCE_UNPARSEABLE, null, src.path(), line,
                    SafeJavaParser.UNPARSEABLE_MESSAGE));
            if (endpointCandidate) run.complete = false;
            return;
        }
        run.files.add(new ParsedFile(src.path(), src.data().sha256(), m, summary));
        run.index.add(summary, src.path(), src.data().sha256());
    }

    static boolean endpointTokens(String text) {
        return text.contains("@RestController") || text.contains("@Controller") || text.contains("Mapping")
                || text.contains("annotation.RestController") || text.contains("stereotype.Controller");
    }

    static boolean otherTokens(String text) {
        return text.contains("SpringBootApplication") || text.contains("@Value") || text.contains("annotation.Value")
                || text.contains("getenv");
    }

    static boolean mayDeclareConstant(String text) {
        if (!text.contains("String")) return false;
        return (text.contains("static") && text.contains("final")) || text.contains("interface");
    }

    /** Simple constant names (the last part of BAR / Foo.BAR) that mapping paths reference. */
    static Set<String> referencedNames(List<ParsedFile> files) {
        Set<String> names = new TreeSet<>();
        for (ParsedFile f : files) {
            for (SourceSummary.Controller c : f.summary().controllers()) {
                if (c.classMapping() != null) c.classMapping().paths().forEach(e -> names(e, names));
                for (SourceSummary.Handler h : c.handlers()) h.mapping().paths().forEach(e -> names(e, names));
            }
        }
        return names;
    }

    private static void names(Expr e, Set<String> out) {
        if (e instanceof Expr.Name n) out.add(n.name());
        if (e instanceof Expr.Concat c) c.parts().forEach(p -> names(p, out));
    }

    private static Module moduleOf(List<Module> modules, ScannedFile f) {
        if (!RuleTable.isJavaSource(f)) return null;
        for (Module m : modules) {
            if (f.path().startsWith(m.sourceRoot())) return m;
        }
        return null;
    }

    /** One warning per module that has Kotlin sources (checked by name only, never read). */
    private static void kotlin(Run run, List<Module> modules, List<ScannedFile> all) {
        for (Module m : modules) {
            String prefix = ConfigContext.join(m.dir(), "src/main/");
            all.stream().filter(f -> f.path().startsWith(prefix) && f.fileName().endsWith(".kt")).findFirst()
                    .ifPresent(f -> {
                        run.ctx.warn(new Warning(WarningCodes.ENDPOINTS_NOT_COVERED, f.path(), NOT_COVERED_KOTLIN));
                        run.complete = false;
                    });
        }
    }
}

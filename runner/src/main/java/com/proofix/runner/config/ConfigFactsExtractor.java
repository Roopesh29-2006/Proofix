package com.proofix.runner.config;

import com.proofix.runner.config.jdk.JavaVersionConflict;
import com.proofix.runner.config.maven.LoadedPom;
import com.proofix.runner.config.maven.MavenFacts;
import com.proofix.runner.config.spring.SpringConfigFacts;
import com.proofix.runner.detect.MarkerReader;
import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.detect.model.Role;
import com.proofix.runner.detect.model.Stack;
import com.proofix.runner.facts.model.Endpoint;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.source.JavaSources;
import com.proofix.runner.source.SourceFacts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the config-fact extractors for every backend java-maven stack (root and member modules),
 * then the Java source facts ({@link SourceFacts}).
 * Reads files only through the given {@link MarkerReader}; never executes anything.
 */
public final class ConfigFactsExtractor {
    private ConfigFactsExtractor() {}

    public static final String MAVEN_KIND = "java-maven";

    /** endpointsComplete: every endpoint of the analysed backends is listed (see SourceFacts). */
    public record Result(List<FactEntry> facts, List<Warning> warnings, List<Endpoint> endpoints,
                         boolean endpointsComplete) {
        public Result {
            facts = List.copyOf(facts);
            warnings = List.copyOf(warnings);
            endpoints = List.copyOf(endpoints);
        }

        /** No config facts and no endpoints (nothing was analysed). */
        public static Result empty() {
            return new Result(List.of(), List.of(), List.of(), false);
        }
    }

    public static Result extract(ScanResult scan, Detection detection, MarkerReader reader) {
        return extract(scan, detection, reader, JavaSources.MAX_PARSED);
    }

    public static Result extract(ScanResult scan, Detection detection, MarkerReader reader, int maxParsedSources) {
        ConfigContext ctx = new ConfigContext(scan, reader);
        Map<String, LoadedPom> cache = new HashMap<>();
        List<FactEntry> facts = new ArrayList<>();
        Map<String, SpringConfigFacts.EnvAcc> env = new TreeMap<>();
        List<JavaSources.Module> modules = new ArrayList<>();
        for (Stack s : detection.stacks()) {
            if (!MAVEN_KIND.equals(s.kind()) || !Role.BACKEND.equals(s.role())) continue;
            List<String> dirs = new ArrayList<>();
            dirs.add(s.path());
            dirs.addAll(s.members());
            for (String dir : dirs) {
                MavenFacts.Module m = MavenFacts.extract(ctx, dir, cache);
                if (m == null) continue;
                modules.add(new JavaSources.Module(dir, s.path(), m.app()));
                facts.add(m.app()
                        ? JavaVersionConflict.apply(ctx, m.javaVersion(), dir, s.path())
                        : m.javaVersion());
                facts.addAll(m.facts());
                if (m.app()) SpringConfigFacts.extract(ctx, m, env, facts);
            }
        }
        SourceFacts.Result source = SourceFacts.extract(ctx, detection, modules, facts, env, maxParsedSources);
        facts.addAll(SpringConfigFacts.envFacts(env));
        return new Result(facts, ctx.warnings(), source.endpoints(), source.complete());
    }
}

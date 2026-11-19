package com.proofix.runner.config;

import com.proofix.runner.config.maven.LoadedPom;
import com.proofix.runner.config.maven.MavenFacts;
import com.proofix.runner.detect.MarkerReader;
import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.detect.model.Role;
import com.proofix.runner.detect.model.Stack;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the config-fact extractors for every backend java-maven stack (root and member modules).
 * Reads files only through the given {@link MarkerReader}; never executes anything.
 */
public final class ConfigFactsExtractor {
    private ConfigFactsExtractor() {}

    public static final String MAVEN_KIND = "java-maven";

    public record Result(List<FactEntry> facts, List<Warning> warnings) {
        public Result {
            facts = List.copyOf(facts);
            warnings = List.copyOf(warnings);
        }
    }

    public static Result extract(ScanResult scan, Detection detection, MarkerReader reader) {
        ConfigContext ctx = new ConfigContext(scan, reader);
        Map<String, LoadedPom> cache = new HashMap<>();
        List<FactEntry> facts = new ArrayList<>();
        for (Stack s : detection.stacks()) {
            if (!MAVEN_KIND.equals(s.kind()) || !Role.BACKEND.equals(s.role())) continue;
            List<String> dirs = new ArrayList<>();
            dirs.add(s.path());
            dirs.addAll(s.members());
            for (String dir : dirs) {
                MavenFacts.Module m = MavenFacts.extract(ctx, dir, cache);
                if (m == null) continue;
                facts.add(m.javaVersion());
                facts.addAll(m.facts());
            }
        }
        return new Result(facts, ctx.warnings());
    }
}

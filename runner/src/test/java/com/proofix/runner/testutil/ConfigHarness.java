package com.proofix.runner.testutil;

import com.proofix.runner.config.ConfigFactsExtractor;
import com.proofix.runner.detect.MarkerReader;
import com.proofix.runner.detect.StackDetector;
import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.facts.model.EnvVarFact;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.scan.Limits;
import com.proofix.runner.scan.Scanner;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.Warning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** Scans a directory and runs the config-fact extractors directly (no CLI). */
public final class ConfigHarness {
    private ConfigHarness() {}

    public record Run(List<FactEntry> facts, List<Warning> warnings) {

        public List<Fact> all(String module, String name) {
            return facts.stream().filter(e -> e instanceof Fact).map(e -> (Fact) e)
                    .filter(f -> f.module().equals(module) && f.name().equals(name)).toList();
        }

        public Fact one(String module, String name) {
            List<Fact> a = all(module, name);
            if (a.size() != 1) throw new AssertionError("expected one " + name + " in " + module + " but got " + a);
            return a.get(0);
        }

        public Fact one(String module, String name, String profile) {
            List<Fact> a = all(module, name).stream()
                    .filter(f -> profile == null ? f.profile() == null : profile.equals(f.profile())).toList();
            if (a.size() != 1) throw new AssertionError("expected one " + name + "/" + profile + " but got " + a);
            return a.get(0);
        }

        public List<String> values(String module, String name) {
            return all(module, name).stream().map(Fact::value).sorted().toList();
        }

        public EnvVarFact env(String name) {
            return facts.stream().filter(e -> e instanceof EnvVarFact && e.name().equals("env." + name))
                    .map(e -> (EnvVarFact) e).findFirst().orElse(null);
        }

        public boolean warned(String code, String path) {
            return warnings.stream().anyMatch(w -> w.code().equals(code) && (path == null || path.equals(w.path())));
        }
    }

    public static Run run(Path repo) {
        return run(repo, Limits.DEFAULTS);
    }

    public static Run run(Path repo, Limits limits) {
        try {
            ScanResult scan = Scanner.forRepo(limits).scan(repo);
            Detection d = new StackDetector(limits.maxMarkerBytes()).detect(scan);
            ConfigFactsExtractor.Result r = ConfigFactsExtractor.extract(scan, d,
                    new MarkerReader(scan.root(), limits.maxMarkerBytes()));
            return new Run(r.facts(), r.warnings());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean cites(Fact f, String path, int line) {
        return f.evidence().stream().anyMatch(e -> e.path().equals(path) && e.line() != null && e.line() == line);
    }

    public static boolean cites(Fact f, String rule) {
        return f.evidence().stream().map(FactEvidence::rule).anyMatch(rule::equals);
    }
}

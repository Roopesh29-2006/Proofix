package com.proofix.runner.detect;

import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.detect.model.Evidence;
import com.proofix.runner.detect.model.MatchedFile;
import com.proofix.runner.detect.model.Role;
import com.proofix.runner.detect.model.Stack;
import com.proofix.runner.detect.model.TestTarget;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Turns a {@link ScanResult} into stacks and a test target. Uses only the file listing plus the
 * contents of marker files (via {@link MarkerReader}).
 */
public final class StackDetector {

    public static final Set<String> AUXILIARY_SEGMENTS = Set.of(
            "examples", "example", "samples", "docs", "test", "tests", "fixtures", "scripts", "benchmarks");

    /** Root first, then by path, then by id. */
    public static final Comparator<Stack> STACK_ORDER = Comparator
            .comparing((Stack s) -> !s.path().equals("."))
            .thenComparing(Stack::path)
            .thenComparing((Stack s) -> s.id());

    private record Match(String dir, Rule rule, int ruleIndex, List<ScannedFile> markers) {
        int depth() {
            return dir.equals(".") ? 0 : dir.split("/").length;
        }
    }

    private static final class Builder {
        final Match root;
        final String parent;
        final List<Match> members = new ArrayList<>();

        Builder(Match root, String parent) {
            this.root = root;
            this.parent = parent;
        }

        String id() {
            return Stack.id(root.rule().kind(), root.dir());
        }
    }

    private final long maxMarkerBytes;

    public StackDetector(long maxMarkerBytes) {
        this.maxMarkerBytes = maxMarkerBytes;
    }

    public Detection detect(ScanResult scan) {
        Map<String, List<ScannedFile>> byDir = new TreeMap<>();
        for (ScannedFile f : scan.files()) {
            byDir.computeIfAbsent(f.dir(), d -> new ArrayList<>()).add(f);
        }

        // B1 + B2: per directory, the first matching rule of each ecosystem.
        List<Match> matches = new ArrayList<>();
        for (Map.Entry<String, List<ScannedFile>> e : byDir.entrySet()) {
            List<String> names = e.getValue().stream().map(ScannedFile::fileName).toList();
            Set<String> ecosystemsDone = new TreeSet<>();
            for (int i = 0; i < RuleTable.RULES.size(); i++) {
                Rule rule = RuleTable.RULES.get(i);
                if (ecosystemsDone.contains(rule.ecosystem()) || !rule.matchesDir(names)) continue;
                ecosystemsDone.add(rule.ecosystem());
                List<ScannedFile> markers = e.getValue().stream()
                        .filter(f -> rule.isMarker(f.fileName()))
                        .sorted(Comparator.comparing(ScannedFile::path))
                        .toList();
                matches.add(new Match(e.getKey(), rule, i, markers));
            }
        }
        matches.sort(Comparator.comparingInt(Match::depth).thenComparing(Match::dir).thenComparingInt(Match::ruleIndex));

        // B3: fold same-kind descendants into their nearest same-ecosystem ancestor.
        Map<String, Match> byDirAndEcosystem = new HashMap<>();
        Map<Match, Builder> owner = new HashMap<>();
        List<Builder> builders = new ArrayList<>();
        for (Match m : matches) {
            Match ancestor = nearestAncestor(m, byDirAndEcosystem);
            if (ancestor != null && ancestor.rule().kind().equals(m.rule().kind())) {
                Builder b = owner.get(ancestor);
                b.members.add(m);
                owner.put(m, b);
            } else {
                Builder b = new Builder(m, ancestor == null ? null : owner.get(ancestor).id());
                builders.add(b);
                owner.put(m, b);
            }
            byDirAndEcosystem.put(key(m.dir(), m.rule().ecosystem()), m);
        }

        // B4-B6: confirm, assign roles, collect evidence.
        MarkerReader reader = new MarkerReader(scan.root(), maxMarkerBytes);
        List<Warning> warnings = new ArrayList<>();
        List<Stack> stacks = new ArrayList<>();
        for (Builder b : builders) {
            Set<String> dirNames = byDir.get(b.root.dir()).stream()
                    .map(ScannedFile::fileName).collect(Collectors.toSet());
            Confirmation c = confirm(b.root, dirNames, reader);
            if (Confirmation.MARKER_UNPARSEABLE.equals(c.reason())) {
                for (ScannedFile m : unparseableMarkers(b.root, reader)) {
                    warnings.add(new Warning(WarningCodes.MARKER_UNPARSEABLE, m.path(), "marker file could not be parsed"));
                }
            }

            List<MatchedFile> matched = new ArrayList<>();
            List<Match> all = new ArrayList<>();
            all.add(b.root);
            all.addAll(b.members);
            for (Match m : all) {
                for (ScannedFile f : m.markers()) {
                    MarkerReader.MarkerData d = reader.read(f);
                    matched.add(new MatchedFile(f.path(), d.sha256(), d.size()));
                }
            }
            matched.sort(Comparator.comparing(MatchedFile::path));

            String role = isAuxiliary(b.root.dir()) ? Role.AUXILIARY : c.role();
            List<String> members = b.members.stream().map(Match::dir).sorted().toList();
            stacks.add(new Stack(b.id(), b.root.rule().kind(), b.root.dir(), role, b.parent, members,
                    new Evidence(b.root.rule().id(), c.confirmed(), c.reason(), matched)));
        }
        stacks.sort(STACK_ORDER);
        warnings.addAll(reader.warnings());

        // B8
        if (stacks.isEmpty()) {
            scan.files().stream()
                    .filter(f -> f.fileName().equalsIgnoreCase("Dockerfile"))
                    .map(ScannedFile::path)
                    .sorted()
                    .findFirst()
                    .ifPresent(p -> warnings.add(new Warning(WarningCodes.DOCKERFILE_ONLY, p,
                            "Dockerfile found but no supported stack detected")));
        }
        warnings.sort(Warning.ORDER);
        return new Detection(stacks, testTarget(stacks), warnings);
    }

    private static Confirmation confirm(Match m, Set<String> dirNames, MarkerReader reader) {
        String kind = m.rule().kind();
        switch (kind) {
            case "node":
                return NodeConfirmer.confirmNode(reader.read(file(m, "package.json")));
            case "nextjs":
                return NodeConfirmer.confirmNext(reader.read(file(m, "package.json")));
            case "python":
                return PythonConfirmer.confirm(dirNames, m.markers(), reader);
            default:
                return new Confirmation(true, Role.BACKEND, "marker " + m.markers().get(0).fileName());
        }
    }

    private static List<ScannedFile> unparseableMarkers(Match m, MarkerReader reader) {
        List<ScannedFile> out = new ArrayList<>();
        for (ScannedFile f : m.markers()) {
            MarkerReader.MarkerData d = reader.read(f);
            if (!d.readable()) continue;
            boolean bad = switch (m.rule().kind()) {
                case "node", "nextjs" -> f.fileName().equals("package.json") && NodeConfirmer.isUnparseable(d);
                case "python" -> PythonConfirmer.isUnparseable(d.bytes());
                default -> false;
            };
            if (bad) out.add(f);
        }
        return out;
    }

    private static ScannedFile file(Match m, String name) {
        return m.markers().stream().filter(f -> f.fileName().equals(name)).findFirst().orElseThrow();
    }

    private static Match nearestAncestor(Match m, Map<String, Match> byDirAndEcosystem) {
        String dir = m.dir();
        while (!dir.equals(".")) {
            int i = dir.lastIndexOf('/');
            dir = i < 0 ? "." : dir.substring(0, i);
            Match a = byDirAndEcosystem.get(key(dir, m.rule().ecosystem()));
            if (a != null) return a;
        }
        return null;
    }

    private static String key(String dir, String ecosystem) {
        return ecosystem + "|" + dir;
    }

    static boolean isAuxiliary(String path) {
        if (path.equals(".")) return false;
        for (String seg : path.split("/")) {
            if (AUXILIARY_SEGMENTS.contains(seg.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    static TestTarget testTarget(List<Stack> stacks) {
        if (stacks.isEmpty()) {
            return new TestTarget(null, TestTarget.UNRESOLVED, TestTarget.NO_STACK_DETECTED);
        }
        Map<String, List<Stack>> byRole = new LinkedHashMap<>();
        for (Stack s : stacks) byRole.computeIfAbsent(s.role(), r -> new ArrayList<>()).add(s);
        List<Stack> backends = byRole.getOrDefault(Role.BACKEND, List.of());
        List<Stack> frontends = byRole.getOrDefault(Role.FRONTEND, List.of());
        if (backends.size() == 1) {
            return new TestTarget(backends.get(0).id(), TestTarget.KNOWN, TestTarget.SINGLE_BACKEND);
        }
        if (backends.size() > 1) {
            return new TestTarget(null, TestTarget.UNRESOLVED, TestTarget.MULTIPLE_BACKENDS);
        }
        if (frontends.size() == 1) {
            return new TestTarget(frontends.get(0).id(), TestTarget.KNOWN, TestTarget.ONLY_STACK);
        }
        if (frontends.size() > 1) {
            return new TestTarget(null, TestTarget.UNRESOLVED, TestTarget.MULTIPLE_FRONTENDS);
        }
        return new TestTarget(null, TestTarget.UNRESOLVED, TestTarget.NO_TESTABLE_STACK);
    }
}

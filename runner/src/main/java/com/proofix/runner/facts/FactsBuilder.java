package com.proofix.runner.facts;

import com.proofix.runner.config.ConfigFactsExtractor;
import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.facts.model.Facts;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles facts.json from the scan, the detection, the secret-file warnings, the config facts and
 * the Java source facts (endpoints).
 */
public final class FactsBuilder {
    private FactsBuilder() {}

    private static final Set<String> LIMIT_CODES = Set.of(
            WarningCodes.DEPTH_LIMIT_HIT, WarningCodes.FILE_LIMIT_HIT, WarningCodes.FILE_TOO_LARGE,
            WarningCodes.SOURCE_LIMIT_HIT);

    public static Facts build(ScanResult scan, Detection detection, List<Warning> secretWarnings) {
        return build(scan, detection, secretWarnings, ConfigFactsExtractor.Result.empty());
    }

    public static Facts build(ScanResult scan, Detection detection, List<Warning> secretWarnings,
                              ConfigFactsExtractor.Result config) {
        List<Warning> warnings = new ArrayList<>();
        warnings.addAll(scan.warnings());
        warnings.addAll(detection.warnings());
        warnings.addAll(secretWarnings);
        warnings.addAll(config.warnings());
        warnings.sort(Warning.ORDER);
        List<Warning> unique = new ArrayList<>();
        for (Warning w : warnings) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(w)) unique.add(w);
        }

        TreeSet<String> limitsHit = new TreeSet<>();
        for (Warning w : unique) {
            if (LIMIT_CODES.contains(w.code())) limitsHit.add(w.code());
        }

        return new Facts(
                Facts.SCHEMA_VERSION,
                new Facts.Repo(scan.git().commitSha(), scan.git().dirty(), scan.fileSource()),
                new Facts.Scan(scan.files().size(), scan.skippedDirs(), new ArrayList<>(limitsHit)),
                detection.stacks(),
                detection.testTarget(),
                config.facts(),
                config.endpoints(),
                config.endpointsComplete(),
                unique);
    }
}

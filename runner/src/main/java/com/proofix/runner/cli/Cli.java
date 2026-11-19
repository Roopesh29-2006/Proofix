package com.proofix.runner.cli;

import com.proofix.runner.config.ConfigFactsExtractor;
import com.proofix.runner.detect.MarkerReader;
import com.proofix.runner.detect.StackDetector;
import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.facts.FactsBuilder;
import com.proofix.runner.facts.FactsWriter;
import com.proofix.runner.facts.model.Facts;
import com.proofix.runner.scan.Scanner;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.secrets.SecretFiles;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * The runner command line. Exit codes: 0 success (warnings allowed), 2 usage error,
 * 3 repoPath is not a readable directory, 4 output could not be written, 1 unexpected failure.
 * Logs contain counts, warning codes and relative paths only; never file contents.
 */
public final class Cli {

    public static final int OK = 0;
    public static final int INTERNAL_ERROR = 1;
    public static final int USAGE_ERROR = 2;
    public static final int REPO_UNREADABLE = 3;
    public static final int OUTPUT_UNWRITABLE = 4;

    private final Function<CliOptions, Scanner> scannerFactory;

    public Cli() {
        this(o -> Scanner.forRepo(o.limits()));
    }

    /** Tests inject a scanner (e.g. with a missing git executable). */
    public Cli(Function<CliOptions, Scanner> scannerFactory) {
        this.scannerFactory = scannerFactory;
    }

    public int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions opts;
        try {
            opts = CliOptions.parse(args);
        } catch (CliOptions.UsageException e) {
            err.println(e.getMessage());
            return USAGE_ERROR;
        }
        Path repo = opts.repoPath();
        if (!Files.isDirectory(repo) || !Files.isReadable(repo)) {
            err.println("error: repoPath is not a readable directory");
            return REPO_UNREADABLE;
        }

        byte[] json;
        Facts facts;
        try {
            ScanResult scan = scannerFactory.apply(opts).scan(repo);
            Detection detection = new StackDetector(opts.limits().maxMarkerBytes()).detect(scan);
            List<Warning> secrets = SecretFiles.find(scan);
            ConfigFactsExtractor.Result config = ConfigFactsExtractor.extract(scan, detection,
                    new MarkerReader(scan.root(), opts.limits().maxMarkerBytes()));
            facts = FactsBuilder.build(scan, detection, secrets, config);
            json = FactsWriter.toBytes(facts);
        } catch (IOException | RuntimeException e) {
            err.println("error: scan failed (" + e.getClass().getSimpleName() + ")");
            return INTERNAL_ERROR;
        }

        if (opts.out() != null) {
            try {
                Files.write(opts.out(), json);
            } catch (IOException e) {
                err.println("error: could not write output file");
                return OUTPUT_UNWRITABLE;
            }
        } else {
            out.write(json, 0, json.length);
            out.flush();
        }
        summarize(facts, err);
        return OK;
    }

    private static void summarize(Facts f, PrintStream err) {
        err.println("scan: " + f.scan().filesSeen() + " files via " + f.repo().fileSource()
                + ", " + f.stacks().size() + " stack(s), " + f.facts().size() + " fact(s), test target "
                + (f.testTarget().stackId() != null ? f.testTarget().stackId() : f.testTarget().status())
                + " (" + f.testTarget().reason() + ")");
        for (Warning w : f.warnings()) {
            err.println("warning: " + w.code() + (w.path() != null ? " " + w.path() : ""));
        }
    }
}

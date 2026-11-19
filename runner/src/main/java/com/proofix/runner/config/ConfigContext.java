package com.proofix.runner.config;

import com.proofix.runner.detect.MarkerReader;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared state for one config-facts run. Files are looked up only among the scan's files (which
 * already passed PathGuard) and read only through {@link MarkerReader}.
 */
public final class ConfigContext {

    /** A file that was read: its scan entry and the bounded, hashed bytes. */
    public record Source(ScannedFile file, MarkerReader.MarkerData data) {
        public String path() {
            return file.path();
        }
    }

    private final ScanResult scan;
    private final MarkerReader reader;
    private final String commitSha;
    private final Map<String, ScannedFile> byPath = new HashMap<>();
    private final List<Warning> warnings = new ArrayList<>();

    public ConfigContext(ScanResult scan, MarkerReader reader) {
        this.scan = scan;
        this.reader = reader;
        this.commitSha = scan.git().commitSha();
        for (ScannedFile f : scan.files()) byPath.put(f.path(), f);
    }

    public ScanResult scan() {
        return scan;
    }

    /** The scanned file at this repo-relative path, or null. */
    public ScannedFile file(String path) {
        return byPath.get(path);
    }

    /** Reads an allowlisted file; null (with a MarkerReader warning) when it was not readable. */
    public Source read(ScannedFile f) {
        MarkerReader.MarkerData d = reader.read(f);
        return d.readable() ? new Source(f, d) : null;
    }

    public FactEvidence cite(Source src, String rule, Integer line) {
        return new FactEvidence(rule, src.path(), line, commitSha, src.data().sha256());
    }

    public void warn(Warning w) {
        warnings.add(w);
    }

    /** Warnings raised here plus those of the reader. */
    public List<Warning> warnings() {
        List<Warning> all = new ArrayList<>(warnings);
        all.addAll(reader.warnings());
        return all;
    }

    /** Normalises "a/b/../c" style paths; returns null if the result would leave the repo root. */
    public static String normalize(String path) {
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(seg);
            }
        }
        return out.isEmpty() ? "." : String.join("/", out);
    }

    /** dir "." joined with a child: "pom.xml"; otherwise "dir/child". */
    public static String join(String dir, String child) {
        return dir.equals(".") ? child : dir + "/" + child;
    }
}

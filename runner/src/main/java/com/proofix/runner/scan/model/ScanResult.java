package com.proofix.runner.scan.model;

import java.nio.file.Path;
import java.util.List;

/** Output of the scanner. Contains file names and sizes only; never file contents. */
public record ScanResult(Path root, List<ScannedFile> files, List<String> skippedDirs,
                         GitInfo git, String fileSource, List<Warning> warnings) {
    public ScanResult {
        files = List.copyOf(files);
        skippedDirs = List.copyOf(skippedDirs);
        warnings = List.copyOf(warnings);
    }
}

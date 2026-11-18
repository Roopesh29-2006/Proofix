package com.proofix.runner.scan;

import com.proofix.runner.scan.model.Warning;

import java.nio.file.Path;
import java.util.List;

/**
 * Produces the raw list of candidate paths below a root. Paths are untrusted: the
 * {@link Scanner} runs every one of them through {@link PathGuard}.
 */
public interface FileSource {

    Listing list(Path root) throws FileSourceException;

    /**
     * @param source       "git" or "walk"
     * @param paths        candidate relative paths (any separator; validated later)
     * @param skippedDirs  skip-list directories pruned by the source itself
     * @param depthPruned  directories pruned by the source because of the depth limit
     * @param warnings     warnings raised by the source
     */
    record Listing(String source, List<String> paths, List<String> skippedDirs,
                   List<String> depthPruned, List<Warning> warnings) {
        public Listing {
            paths = List.copyOf(paths);
            skippedDirs = List.copyOf(skippedDirs);
            depthPruned = List.copyOf(depthPruned);
            warnings = List.copyOf(warnings);
        }
    }

    /** Thrown when the source cannot produce a listing; {@code code} becomes the fallback warning. */
    final class FileSourceException extends Exception {
        private final String code;

        public FileSourceException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

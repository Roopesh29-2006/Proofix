package com.proofix.runner.scan;

import com.proofix.runner.scan.model.Warning;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Fallback file source: walks the directory tree WITHOUT following links. Directories that are
 * links or junctions (or whose real path is not where they appear to be) are never entered.
 */
public final class WalkFileSource implements FileSource {

    private final Limits limits;

    public WalkFileSource(Limits limits) {
        this.limits = limits;
    }

    @Override
    public Listing list(Path root) throws FileSourceException {
        List<String> paths = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> depthPruned = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new FileSourceException(WarningCodes.PATH_UNREADABLE, "root unreadable");
        }

        try {
            // No FileVisitOption.FOLLOW_LINKS: links are reported, never traversed.
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(root)) return FileVisitResult.CONTINUE;
                            Path relPath = root.relativize(dir);
                            String rel = PathGuard.toSlashes(relPath);
                            if (attrs.isSymbolicLink() || attrs.isOther()) {
                                // isOther: Windows junctions / mount points report as directories
                                warnings.add(new Warning(WarningCodes.SYMLINK_SKIPPED, rel,
                                        "linked directory not followed"));
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (SkipList.isSkipped(dir.getFileName().toString())) {
                                skipped.add(rel);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            try {
                                if (!dir.toRealPath().equals(realRoot.resolve(relPath))) {
                                    warnings.add(new Warning(WarningCodes.SYMLINK_SKIPPED, rel,
                                            "linked directory not followed"));
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            } catch (IOException e) {
                                warnings.add(new Warning(WarningCodes.PATH_UNREADABLE, rel, "directory unreadable"));
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (relPath.getNameCount() > limits.maxDepth()) {
                                depthPruned.add(rel);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            // File symlinks arrive here too (not followed); PathGuard classifies them.
                            paths.add(PathGuard.toSlashes(root.relativize(file)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            warnings.add(new Warning(WarningCodes.PATH_UNREADABLE,
                                    PathGuard.toSlashes(root.relativize(file)), "path unreadable"));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new FileSourceException(WarningCodes.PATH_UNREADABLE, "directory walk failed");
        }
        return new Listing("walk", paths, skipped, depthPruned, warnings);
    }
}

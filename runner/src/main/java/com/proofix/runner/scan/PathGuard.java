package com.proofix.runner.scan;

import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Safety checks applied to every candidate path from every file source. A path is accepted only
 * if it is a regular file that really lives inside the root: no "..", no absolute paths or drive
 * letters, no symlinks, no junctions, and no parent directory that resolves elsewhere.
 */
public final class PathGuard {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** Outcome of a check: exactly one of file / warning is set, or neither (silent drop). */
    public record Verdict(ScannedFile file, Warning warning) {
        static Verdict accept(ScannedFile f) {
            return new Verdict(f, null);
        }

        static Verdict reject(String code, String path, String message) {
            return new Verdict(null, new Warning(code, path, message));
        }

        static final Verdict SILENT_DROP = new Verdict(null, null);
    }

    private final Path root;
    private final Path realRoot;
    private final Map<Path, Boolean> dirInsideCache = new HashMap<>();

    public PathGuard(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.realRoot = this.root.toRealPath();
    }

    public static String toSlashes(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) sb.append('/');
            sb.append(relative.getName(i).toString());
        }
        return sb.toString();
    }

    /**
     * Purely lexical check. Returns the normalized relative path ('/' separators), or null if the
     * path is empty, absolute, has a drive letter, contains "..", or would leave the root.
     */
    public String lexical(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) return null;
        String s = raw.replace('\\', '/');
        if (s.startsWith("/")) return null;
        if (s.length() >= 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':') return null;
        List<String> segs = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            segs.add(seg);
        }
        if (segs.isEmpty()) return null;
        String rel = String.join("/", segs);
        try {
            Path p = root.resolve(rel).normalize();
            if (!p.startsWith(root) || p.equals(root)) return null;
        } catch (InvalidPathException e) {
            return null;
        }
        return rel;
    }

    /** Filesystem check of a path that already passed {@link #lexical}. Never follows links. */
    public Verdict check(String rel) {
        Path p = root.resolve(rel);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return Verdict.SILENT_DROP; // deleted but still tracked
        } catch (IOException e) {
            return Verdict.reject(WarningCodes.PATH_UNREADABLE, rel, "path unreadable");
        }
        if (attrs.isSymbolicLink()) {
            return Verdict.reject(WarningCodes.SYMLINK_SKIPPED, rel, "symlink not followed");
        }
        if (attrs.isDirectory()) {
            if (attrs.isOther() || !realPathIsExpected(p, rel)) {
                return Verdict.reject(WarningCodes.SYMLINK_SKIPPED, rel, "linked directory not followed");
            }
            return Verdict.reject(WarningCodes.SUBMODULE_SKIPPED, rel, "directory entry (submodule) skipped");
        }
        if (attrs.isOther() && !WINDOWS) {
            // fifo, socket or device; on Windows "other" also covers cloud placeholders, which
            // are accepted below only if their real path is exactly where they appear to be.
            return Verdict.reject(WarningCodes.SPECIAL_FILE_SKIPPED, rel, "special file skipped");
        }
        Path parent = p.getParent();
        if (!parentInside(parent)) {
            return Verdict.reject(WarningCodes.SYMLINK_SKIPPED, rel, "path goes through a linked directory");
        }
        if (attrs.isOther() && !realPathIsExpected(p, rel)) {
            return Verdict.reject(WarningCodes.SYMLINK_SKIPPED, rel, "reparse point not followed");
        }
        return Verdict.accept(new ScannedFile(rel, attrs.size()));
    }

    private boolean realPathIsExpected(Path p, String rel) {
        try {
            return p.toRealPath().equals(realRoot.resolve(rel));
        } catch (IOException e) {
            return false;
        }
    }

    /** The parent directory's real path must be exactly its apparent path below the real root. */
    private boolean parentInside(Path parent) {
        return dirInsideCache.computeIfAbsent(parent, d -> {
            if (d.equals(root)) return true;
            try {
                Path relDir = root.relativize(d);
                return d.toRealPath().equals(realRoot.resolve(relDir));
            } catch (IOException e) {
                return false;
            }
        });
    }
}

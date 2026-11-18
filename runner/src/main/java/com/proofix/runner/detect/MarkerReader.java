package com.proofix.runner.detect;

import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.secrets.SecretFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The only class that reads file contents. It reads marker files only, at most maxBytes, without
 * following links, and refuses secret-file names outright.
 */
public final class MarkerReader {

    /** bytes is null when the file was too large or unreadable. */
    public record MarkerData(byte[] bytes, String sha256, long size, boolean tooLarge) {
        public boolean readable() {
            return bytes != null;
        }
    }

    private final Path root;
    private final long maxBytes;
    private final Map<String, MarkerData> cache = new HashMap<>();
    private final List<Warning> warnings = new ArrayList<>();

    public MarkerReader(Path root, long maxBytes) {
        this.root = root;
        this.maxBytes = maxBytes;
    }

    public MarkerData read(ScannedFile file) {
        if (SecretFiles.isSecretName(file.fileName()) || !RuleTable.isAnyMarker(file.fileName())) {
            throw new IllegalArgumentException("refusing to read a non-marker file");
        }
        return cache.computeIfAbsent(file.path(), k -> load(file));
    }

    public List<Warning> warnings() {
        return warnings;
    }

    private MarkerData load(ScannedFile file) {
        if (file.size() > maxBytes) {
            warnings.add(tooLarge(file));
            return new MarkerData(null, null, file.size(), true);
        }
        Path p = root.resolve(file.path());
        try {
            // Re-check at read time: the file may have been replaced since the scan.
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isDirectory()) {
                warnings.add(new Warning(WarningCodes.SYMLINK_SKIPPED, file.path(),
                        "marker file is no longer a regular file; not read"));
                return new MarkerData(null, null, file.size(), false);
            }
            if (attrs.size() > maxBytes) {
                warnings.add(tooLarge(file));
                return new MarkerData(null, null, attrs.size(), true);
            }
        } catch (IOException e) {
            warnings.add(new Warning(WarningCodes.PATH_UNREADABLE, file.path(), "marker file unreadable"));
            return new MarkerData(null, null, file.size(), false);
        }
        try (SeekableByteChannel ch = Files.newByteChannel(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = readBounded(ch, maxBytes);
            if (bytes == null) { // file grew after the size check
                warnings.add(tooLarge(file));
                return new MarkerData(null, null, file.size(), true);
            }
            return new MarkerData(bytes, sha256(bytes), bytes.length, false);
        } catch (IOException e) {
            warnings.add(new Warning(WarningCodes.PATH_UNREADABLE, file.path(), "marker file unreadable"));
            return new MarkerData(null, null, file.size(), false);
        }
    }

    /**
     * Reads at most maxBytes; returns null as soon as more than maxBytes are available, so a file
     * that grows during the read is never buffered beyond the limit.
     */
    static byte[] readBounded(SeekableByteChannel ch, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        long total = 0;
        int n;
        while ((n = ch.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) return null;
            out.write(buf.array(), 0, n);
            buf.clear();
        }
        return out.toByteArray();
    }

    private Warning tooLarge(ScannedFile file) {
        return new Warning(WarningCodes.FILE_TOO_LARGE, file.path(),
                "marker file larger than " + maxBytes + " bytes; not read or hashed");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

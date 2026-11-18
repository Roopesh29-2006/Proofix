package com.proofix.runner.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Copies a fixture tree from test resources into a temp directory. Names that the repository's
 * .gitignore would hide are stored with placeholders and renamed on the way:
 * {@code __node_modules -> node_modules}, {@code __target -> target}.
 * No .env file is ever committed; tests create one with a random canary value.
 */
public final class Fixtures {
    private Fixtures() {}

    private static final Map<String, String> RENAMES = Map.of(
            "__node_modules", "node_modules",
            "__target", "target");

    public static Path load(String name, Path tempDir) {
        try {
            URL url = Fixtures.class.getResource("/fixtures/" + name);
            if (url == null) throw new IllegalArgumentException("no fixture " + name);
            Path src = Path.of(url.toURI());
            Path dest = tempDir.resolve(name);
            Files.createDirectories(dest);
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path rel = src.relativize(p);
                    Path target = dest;
                    for (Path seg : rel) {
                        String s = seg.toString();
                        target = target.resolve(RENAMES.getOrDefault(s, s));
                    }
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target);
                }
            }
            return dest;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path root, String rel, String content) {
        try {
            Path p = root.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

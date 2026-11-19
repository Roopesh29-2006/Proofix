package com.proofix.runner.detect;

import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.Links;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [SEC] MarkerReader re-checks a marker at read time and never reads past the size limit. */
class MarkerReaderTest {

    @TempDir
    Path tmp;

    private boolean warned(MarkerReader r, String code, String path) {
        return r.warnings().stream().anyMatch(w -> w.code().equals(code) && w.path().equals(path));
    }

    @Test
    void markerReplacedBySymlinkAfterScanIsNotRead() {
        String canary = "canary-" + UUID.randomUUID();
        Path root = tmp.resolve("repo");
        Fixtures.write(tmp, "outside/package.json", "{\"name\":\"" + canary + "\"}");
        Fixtures.write(root, "README.md", "x");
        // The scan saw a regular package.json; it is swapped for a link before the read.
        ScannedFile seen = new ScannedFile("package.json", 20);
        Links.symlinkOrSkip(root.resolve("package.json"), tmp.resolve("outside/package.json"));

        MarkerReader r = new MarkerReader(root, 1000);
        MarkerReader.MarkerData d = r.read(seen);
        assertFalse(d.readable());
        assertNull(d.sha256());
        assertTrue(warned(r, WarningCodes.SYMLINK_SKIPPED, "package.json"));
    }

    @Test
    void noFollowOpenRefusesSymlinkOnItsOwn() {
        // Second layer: even without the attribute re-check, the NOFOLLOW open itself fails.
        Fixtures.write(tmp, "target.json", "{}");
        Path link = tmp.resolve("link.json");
        Links.symlinkOrSkip(link, tmp.resolve("target.json"));
        assertThrows(IOException.class, () -> {
            try (SeekableByteChannel ch = Files.newByteChannel(link, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                MarkerReader.readBounded(ch, 1000);
            }
        });
    }

    @Test
    void markerReplacedByDirectoryIsNotRead() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(root.resolve("pom.xml"));
        MarkerReader r = new MarkerReader(root, 1000);
        assertFalse(r.read(new ScannedFile("pom.xml", 10)).readable());
        assertTrue(warned(r, WarningCodes.SYMLINK_SKIPPED, "pom.xml"));
    }

    @Test
    void sizeIsRecheckedAtReadTime() {
        Path root = tmp.resolve("repo");
        Fixtures.write(root, "package.json", "{\"description\":\"" + "x".repeat(100) + "\"}");
        // The scan recorded 5 bytes; the file has grown past the 50-byte limit since.
        MarkerReader r = new MarkerReader(root, 50);
        MarkerReader.MarkerData d = r.read(new ScannedFile("package.json", 5));
        assertTrue(d.tooLarge());
        assertNull(d.bytes());
        assertNull(d.sha256());
        assertTrue(warned(r, WarningCodes.FILE_TOO_LARGE, "package.json"));
    }

    @Test
    void boundedReadStopsAsSoonAsTheLimitIsPassed() throws IOException {
        Path f = Files.writeString(tmp.resolve("big.json"), "y".repeat(20_000));
        try (SeekableByteChannel ch = Files.newByteChannel(f, StandardOpenOption.READ)) {
            assertNull(MarkerReader.readBounded(ch, 10_000));
        }
        try (SeekableByteChannel ch = Files.newByteChannel(f, StandardOpenOption.READ)) {
            assertEquals(20_000, MarkerReader.readBounded(ch, 20_000).length);
        }
    }

    @Test
    void allowlistedContentFileIsRead() {
        Path root = tmp.resolve("repo");
        Fixtures.write(root, "api/src/main/resources/application.yml", "server:\n  port: 8080\n");
        MarkerReader.MarkerData d = new MarkerReader(root, 1000)
                .read(new ScannedFile("api/src/main/resources/application.yml", 22));
        assertTrue(d.readable());
        assertEquals(64, d.sha256().length());
    }

    @Test
    void nonAllowlistedFilesAreRefused() {
        MarkerReader r = new MarkerReader(tmp, 1000);
        assertThrows(IllegalArgumentException.class, () -> r.read(new ScannedFile("README.md", 1)));
        // Right name, wrong directory: Spring config is only read under src/main/resources.
        assertThrows(IllegalArgumentException.class, () -> r.read(new ScannedFile("application.yml", 1)));
        assertThrows(IllegalArgumentException.class, () -> r.read(new ScannedFile(".env", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> r.read(new ScannedFile("src/main/resources/.env.production", 1)));
    }

    @Test
    void contentFileReplacedBySymlinkAfterScanIsNotRead() throws IOException {
        String canary = "canary-" + UUID.randomUUID();
        Path root = tmp.resolve("repo");
        Fixtures.write(tmp, "outside/secret.txt", "admin.password: " + canary + "\n");
        String rel = "src/main/resources/application.yml";
        Files.createDirectories(root.resolve(rel).getParent());
        Links.symlinkOrSkip(root.resolve(rel), tmp.resolve("outside/secret.txt"));

        MarkerReader r = new MarkerReader(root, 1000);
        MarkerReader.MarkerData d = r.read(new ScannedFile(rel, 20));
        assertFalse(d.readable());
        assertTrue(warned(r, WarningCodes.SYMLINK_SKIPPED, rel));
    }

    @Test
    void regularMarkerIsReadAndHashed() {
        Path root = tmp.resolve("repo");
        Fixtures.write(root, "go.mod", "module x\n");
        MarkerReader.MarkerData d = new MarkerReader(root, 1000).read(new ScannedFile("go.mod", 9));
        assertEquals("module x\n", new String(d.bytes(), StandardCharsets.UTF_8));
        assertEquals(64, d.sha256().length());
    }
}

package com.proofix.runner.detect;

import com.proofix.runner.detect.model.Role;
import com.proofix.runner.scan.model.ScannedFile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies a Python stack. Entry files are checked by presence only (never read). Marker files
 * are scanned line by line for framework names; setup.py is read as text and never executed.
 */
public final class PythonConfirmer {
    private PythonConfirmer() {}

    public static final List<String> ENTRY_FILES = List.of("main.py", "app.py", "manage.py", "wsgi.py", "asgi.py");
    public static final List<String> FRAMEWORKS = List.of(
            "django", "flask", "fastapi", "starlette", "sanic", "tornado", "aiohttp", "quart", "litestar");

    private static final List<Pattern> PATTERNS = FRAMEWORKS.stream()
            .map(f -> Pattern.compile("(?<![a-z0-9._-])" + Pattern.quote(f) + "(?![a-z0-9_-])"))
            .toList();

    /**
     * @param dirFileNames names of all files in the stack directory (presence only)
     * @param markers      the python marker files in that directory, sorted by path
     */
    public static Confirmation confirm(Set<String> dirFileNames, List<ScannedFile> markers, MarkerReader reader) {
        for (String entry : ENTRY_FILES) {
            if (dirFileNames.contains(entry)) {
                return new Confirmation(true, Role.BACKEND, "entry file " + entry);
            }
        }
        boolean unparseable = false;
        boolean tooLarge = false;
        boolean unreadable = false;
        for (ScannedFile m : markers) {
            MarkerReader.MarkerData d = reader.read(m);
            if (d.tooLarge()) {
                tooLarge = true;
                continue;
            }
            if (!d.readable()) {
                unreadable = true;
                continue;
            }
            String text = decode(d.bytes());
            if (text == null) {
                unparseable = true;
                continue;
            }
            String fw = findFramework(text);
            if (fw != null) {
                return new Confirmation(true, Role.BACKEND, "web framework " + fw + " in " + m.fileName());
            }
        }
        if (unparseable) return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_UNPARSEABLE);
        if (tooLarge) return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_TOO_LARGE);
        if (unreadable) return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_UNREADABLE);
        return new Confirmation(false, Role.TOOLING, "no entry file or web framework");
    }

    /** Returns true if the bytes are not valid UTF-8 text. */
    static boolean isUnparseable(byte[] bytes) {
        return decode(bytes) == null;
    }

    static String findFramework(String text) {
        for (String rawLine : text.split("\n")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.toLowerCase(Locale.ROOT).replace('_', '-');
            if (line.isBlank()) continue;
            for (int i = 0; i < PATTERNS.size(); i++) {
                if (PATTERNS.get(i).matcher(line).find()) return FRAMEWORKS.get(i);
            }
        }
        return null;
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}

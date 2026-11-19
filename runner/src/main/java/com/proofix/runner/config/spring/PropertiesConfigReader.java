package com.proofix.runner.config.spring;

import com.proofix.runner.scan.model.Warning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * [SEC] A small line-tracking reader for application*.properties (java.util.Properties loses line
 * numbers). Decoded as ISO-8859-1 like Spring Boot's loader. Supports # and ! comments, the
 * separators '=', ':' and whitespace, backslash line continuation, the escapes \t \n \r \f \\uXXXX
 * (any other escaped char stands for itself) and Boot's "#---" / "!---" document separators.
 * The line of an entry is the first physical line of its logical line.
 */
public final class PropertiesConfigReader {
    private PropertiesConfigReader() {}

    public static ConfigDocs read(String path, byte[] bytes, String fileProfile) {
        List<Warning> warnings = new ArrayList<>();
        List<ConfigDocs.Doc> docs = new ArrayList<>();
        try {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            String[] phys = text.split("\r\n|\r|\n", -1);
            List<String[]> raw = new ArrayList<>();
            List<Integer> lines = new ArrayList<>();
            int count = 0;
            int i = 0;
            while (i < phys.length) {
                String first = stripLeading(phys[i]);
                int startLine = i + 1;
                if (first.equals("#---") || first.equals("!---")) {
                    docs.add(ConfigDocs.doc(raw, lines, fileProfile, path, warnings));
                    raw = new ArrayList<>();
                    lines = new ArrayList<>();
                    i++;
                    continue;
                }
                if (first.isEmpty() || first.charAt(0) == '#' || first.charAt(0) == '!') {
                    i++;
                    continue;
                }
                StringBuilder logical = new StringBuilder();
                String cur = first;
                while (true) {
                    if (endsWithContinuation(cur) && i + 1 < phys.length) {
                        logical.append(cur, 0, cur.length() - 1);
                        i++;
                        cur = stripLeading(phys[i]);
                    } else {
                        logical.append(endsWithContinuation(cur) ? cur.substring(0, cur.length() - 1) : cur);
                        i++;
                        break;
                    }
                }
                if (++count > ConfigDocs.MAX_ENTRIES) return ConfigDocs.failed(path, true);
                String[] kv = split(logical.toString());
                raw.add(kv);
                lines.add(startLine);
            }
            docs.add(ConfigDocs.doc(raw, lines, fileProfile, path, warnings));
        } catch (RuntimeException e) {
            return ConfigDocs.failed(path, false);
        }
        return new ConfigDocs(docs, warnings);
    }

    private static String stripLeading(String s) {
        int k = 0;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t' || s.charAt(k) == '\f')) k++;
        return s.substring(k);
    }

    private static boolean endsWithContinuation(String s) {
        int n = 0;
        for (int k = s.length() - 1; k >= 0 && s.charAt(k) == '\\'; k--) n++;
        return n % 2 == 1;
    }

    /** Splits one logical line into unescaped key and value. */
    static String[] split(String line) {
        StringBuilder key = new StringBuilder();
        int k = 0;
        while (k < line.length()) {
            char c = line.charAt(k);
            if (c == '\\' && k + 1 < line.length()) {
                k = unescape(line, k, key);
                continue;
            }
            if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f') break;
            key.append(c);
            k++;
        }
        while (k < line.length() && (line.charAt(k) == ' ' || line.charAt(k) == '\t' || line.charAt(k) == '\f')) k++;
        if (k < line.length() && (line.charAt(k) == '=' || line.charAt(k) == ':')) k++;
        while (k < line.length() && (line.charAt(k) == ' ' || line.charAt(k) == '\t' || line.charAt(k) == '\f')) k++;
        StringBuilder value = new StringBuilder();
        while (k < line.length()) {
            char c = line.charAt(k);
            if (c == '\\' && k + 1 < line.length()) {
                k = unescape(line, k, value);
                continue;
            }
            value.append(c);
            k++;
        }
        return new String[] {key.toString(), value.toString()};
    }

    /** Handles the escape starting at s[k] == '\\'; returns the index after it. */
    private static int unescape(String s, int k, StringBuilder out) {
        char e = s.charAt(k + 1);
        switch (e) {
            case 't' -> out.append('\t');
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case 'f' -> out.append('\f');
            case 'u' -> {
                if (k + 6 > s.length()) throw new IllegalArgumentException();
                out.append((char) Integer.parseInt(s.substring(k + 2, k + 6), 16));
                return k + 6;
            }
            default -> out.append(e);
        }
        return k + 2;
    }
}

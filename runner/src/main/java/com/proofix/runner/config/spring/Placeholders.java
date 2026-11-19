package com.proofix.runner.config.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a Spring value into literal text and ${name} / ${name:default} placeholders. A default
 * may itself contain placeholders; it is kept as text. An unclosed "${" is literal text.
 */
public final class Placeholders {
    private Placeholders() {}

    /** Env-style names: only these become env var facts; anything else references a property. */
    public static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** defaultValue is null when there is no default (or when it was withheld as a secret). */
    public record Placeholder(String name, boolean hasDefault, String defaultValue) {
        public boolean envStyle() {
            return ENV_NAME.matcher(name).matches();
        }

        Placeholder withoutDefault() {
            return new Placeholder(name, hasDefault, null);
        }
    }

    /** One piece of a value: text != null for literal text, otherwise a placeholder. */
    public record Segment(String text, Placeholder placeholder) {}

    public record Parsed(List<Segment> segments) {
        public List<Placeholder> placeholders() {
            return segments.stream().filter(s -> s.placeholder() != null).map(Segment::placeholder).toList();
        }

        /** Exactly one placeholder and nothing else but whitespace. */
        public boolean pure() {
            int refs = 0;
            for (Segment s : segments) {
                if (s.placeholder() != null) refs++;
                else if (!s.text().isBlank()) return false;
            }
            return refs == 1;
        }

        /** True when the value carries literal content: non-blank text or a non-empty default. */
        public boolean hasLiteral() {
            for (Segment s : segments) {
                if (s.placeholder() == null && !s.text().isBlank()) return true;
                if (s.placeholder() != null && s.placeholder().hasDefault()
                        && s.placeholder().defaultValue() != null && !s.placeholder().defaultValue().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The value with env-style placeholders replaced by their defaults; null if any placeholder
         * has no default, is not env-style, or its default contains another placeholder.
         */
        public String withDefaults() {
            StringBuilder sb = new StringBuilder();
            for (Segment s : segments) {
                if (s.placeholder() == null) {
                    sb.append(s.text());
                    continue;
                }
                Placeholder p = s.placeholder();
                if (!p.envStyle() || !p.hasDefault() || p.defaultValue() == null || p.defaultValue().contains("${")) {
                    return null;
                }
                sb.append(p.defaultValue());
            }
            return sb.toString();
        }
    }

    public static Parsed parse(String value) {
        List<Segment> out = new ArrayList<>();
        if (value == null) return new Parsed(out);
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) break;
            int end = closing(value, start + 2);
            if (end < 0) break;
            text.append(value, i, start);
            if (!text.isEmpty()) {
                out.add(new Segment(text.toString(), null));
                text.setLength(0);
            }
            out.add(new Segment(null, placeholder(value.substring(start + 2, end))));
            i = end + 1;
        }
        text.append(value.substring(i));
        if (!text.isEmpty()) out.add(new Segment(text.toString(), null));
        return new Parsed(List.copyOf(out));
    }

    /**
     * [SEC] Copy for a secret-looking key: literal text is dropped and every default is withheld
     * (hasDefault kept), so only placeholder names remain.
     */
    public static Parsed redacted(Parsed p) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : p.segments()) {
            if (s.placeholder() != null) out.add(new Segment(null, s.placeholder().withoutDefault()));
        }
        return new Parsed(List.copyOf(out));
    }

    private static Placeholder placeholder(String inner) {
        int depth = 0;
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ':' && depth == 0) {
                return new Placeholder(inner.substring(0, k).trim(), true, inner.substring(k + 1));
            }
        }
        return new Placeholder(inner.trim(), false, null);
    }

    private static int closing(String v, int from) {
        int depth = 1;
        for (int k = from; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '{' && k > 0 && v.charAt(k - 1) == '$') depth++;
            else if (c == '}' && --depth == 0) return k;
        }
        return -1;
    }
}

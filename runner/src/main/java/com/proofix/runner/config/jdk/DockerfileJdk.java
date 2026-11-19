package com.proofix.runner.config.jdk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JDK major of a Dockerfile's FINAL stage. The final FROM is followed through stage
 * aliases ("FROM build"); --platform and other flags are skipped; a registry prefix is allowed.
 * Only eclipse-temurin, openjdk and amazoncorretto images count, and only when the tag starts
 * with a version number ("17-jre-alpine" -> 17, "8u392" -> 8, "1.8" -> 8). "latest", a missing
 * tag, or an image built from ${ARG} gives no answer. Nothing is ever executed or pulled.
 */
public final class DockerfileJdk {
    private DockerfileJdk() {}

    public static final Set<String> IMAGES = Set.of("eclipse-temurin", "openjdk", "amazoncorretto");
    private static final Pattern VERSION = Pattern.compile("^(\\d{1,3})(?:\\.(\\d{1,3}))?(?!\\d)");

    /** major and the 1-based line of the FROM it came from. */
    public record Runtime(String major, int line) {}

    private record Stage(String image, String alias, int line) {}

    /** null when the final stage is not a recognised JDK image with a version tag. */
    public static Runtime parse(byte[] bytes) {
        List<Stage> stages = new ArrayList<>();
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int start = i + 1;
            StringBuilder sb = new StringBuilder();
            String cur = lines[i];
            while (cur.stripTrailing().endsWith("\\") && i + 1 < lines.length) {
                String t = cur.stripTrailing();
                sb.append(t, 0, t.length() - 1).append(' ');
                cur = lines[++i];
            }
            sb.append(cur);
            String ins = sb.toString().strip();
            if (ins.isEmpty() || ins.startsWith("#")) continue;
            String[] tok = ins.split("\\s+");
            if (!tok[0].equalsIgnoreCase("FROM")) continue;
            int k = 1;
            while (k < tok.length && tok[k].startsWith("--")) k++;
            if (k >= tok.length) continue;
            String image = tok[k];
            String alias = (k + 2 < tok.length && tok[k + 1].equalsIgnoreCase("AS")) ? tok[k + 2] : null;
            stages.add(new Stage(image, alias, start));
        }
        if (stages.isEmpty()) return null;
        int idx = stages.size() - 1;
        Stage s = stages.get(idx);
        for (int hops = 0; hops < stages.size(); hops++) {
            int prev = aliasIndex(stages, s.image(), idx);
            if (prev < 0) break;
            idx = prev;
            s = stages.get(prev);
        }
        String major = major(s.image());
        return major == null ? null : new Runtime(major, s.line());
    }

    private static int aliasIndex(List<Stage> stages, String image, int before) {
        for (int j = before - 1; j >= 0; j--) {
            if (stages.get(j).alias() != null && stages.get(j).alias().equalsIgnoreCase(image)) return j;
        }
        return -1;
    }

    static String major(String image) {
        if (image.contains("$")) return null;
        String ref = image;
        int at = ref.indexOf('@');
        if (at >= 0) ref = ref.substring(0, at);
        String last = ref.substring(ref.lastIndexOf('/') + 1);
        int colon = last.indexOf(':');
        String name = (colon < 0 ? last : last.substring(0, colon)).toLowerCase(Locale.ROOT);
        if (!IMAGES.contains(name) || colon < 0) return null;
        return versionMajor(last.substring(colon + 1));
    }

    /** Leading version number of s, with 1.N -> N; null when s does not start with a digit. */
    static String versionMajor(String s) {
        Matcher m = VERSION.matcher(s);
        if (!m.find()) return null;
        String major = m.group(1);
        if (major.equals("1") && m.group(2) != null) major = m.group(2);
        return String.valueOf(Integer.parseInt(major));
    }
}

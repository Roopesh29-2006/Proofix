package com.proofix.runner.source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Joins URL path parts: split on '/' outside {...}, drop empty segments, join with one '/',
 * always a leading '/', never a trailing one ("/" alone stays "/"). Path variable text is kept as
 * written; {id} and {id:regex} name "id", and {*rest} names "rest".
 */
public final class PathJoin {
    private PathJoin() {}

    public static String join(List<String> parts) {
        List<String> segs = new ArrayList<>();
        for (String p : parts) segs.addAll(segments(p));
        return "/" + String.join("/", segs);
    }

    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && depth > 0) depth--;
            if (c == '/' && depth == 0) {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    public static List<String> variables(String path) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0) {
                    String body = path.substring(start, i);
                    int colon = body.indexOf(':');
                    String name = (colon < 0 ? body : body.substring(0, colon)).trim();
                    if (name.startsWith("*")) name = name.substring(1);
                    if (!name.isEmpty()) names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }
}

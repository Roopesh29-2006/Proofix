package com.proofix.runner.config.spring;

import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Output of a config reader: the file's documents (each with a profile) and its warnings. */
public record ConfigDocs(List<Doc> docs, List<Warning> warnings) {

    public static final int MAX_ENTRIES = 10_000;
    public static final String ON_PROFILE = "spring.config.activate.on-profile";
    public static final String DEFAULT_PROFILE = "default";
    public static final String UNPARSEABLE = "config file could not be parsed; no facts taken from it";
    public static final String TOO_MANY = "config file has more than " + MAX_ENTRIES + " entries; no facts taken from it";

    public ConfigDocs {
        docs = List.copyOf(docs);
        warnings = List.copyOf(warnings);
    }

    /** One document. Later entries of the same key win; entries keeps only the winner per key. */
    public record Doc(String profile, Map<String, ConfigEntry> entries) {
        public Doc {
            entries = Map.copyOf(entries);
        }
    }

    /** Thrown inside readers; carries no content. */
    static final class Unparseable extends RuntimeException {
        final boolean tooMany;

        Unparseable(boolean tooMany) {
            super(null, null, false, false);
            this.tooMany = tooMany;
        }
    }

    static ConfigDocs failed(String path, boolean tooMany) {
        return new ConfigDocs(List.of(), List.of(new Warning(WarningCodes.CONFIG_UNPARSEABLE, path,
                tooMany ? TOO_MANY : UNPARSEABLE)));
    }

    /** "application-prod.yml" -> "prod"; "application.yml" -> "default". */
    public static String fileProfile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        return base.startsWith("application-") ? base.substring("application-".length()) : DEFAULT_PROFILE;
    }

    /** Builds a document from raw (key, value, line) triples, applying on-profile and secret rules. */
    static Doc doc(List<String[]> raw, List<Integer> lines, String fileProfile, String path, List<Warning> warnings) {
        Map<String, ConfigEntry> entries = new LinkedHashMap<>();
        String profile = fileProfile;
        List<Warning> local = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String key = raw.get(i)[0];
            String value = raw.get(i)[1];
            if (key.equals(ON_PROFILE)) {
                if (value != null && !value.isBlank()) profile = value.trim();
                continue;
            }
            entries.remove(key);
            entries.put(key, ConfigEntry.of(key, value, lines.get(i), path, local));
        }
        warnings.addAll(local);
        return new Doc(profile, entries);
    }
}

package com.proofix.runner.config.spring;

import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;

import java.util.List;

/**
 * [SEC] One flattened config key. Built only through {@link #of}, which decides before the entry
 * leaves the reader whether the value may be kept:
 * <ul>
 *   <li>non-secret key: value kept as written;</li>
 *   <li>secret-looking key whose value is only placeholders without defaults: value kept (it holds
 *       no secret, e.g. "${DB_PASSWORD}");</li>
 *   <li>secret-looking key with any literal content (text or a non-empty placeholder default):
 *       value = null, secretLiteral = true, and a LITERAL_SECRET warning (key, path, line only).</li>
 * </ul>
 * For secret-looking keys every placeholder default is withheld (defaultValue null). A dropped
 * value is never hashed, logged or placed in an exception.
 */
public record ConfigEntry(String key, String value, int line, boolean secretKey, boolean secretLiteral,
                          Placeholders.Parsed parsed) {

    public static final String LITERAL_SECRET_MESSAGE = "literal secret value in config; value not read";

    public List<Placeholders.Placeholder> placeholders() {
        return parsed.placeholders();
    }

    /** Creates the entry, adding a LITERAL_SECRET warning to {@code warnings} when needed. */
    static ConfigEntry of(String key, String rawValue, int line, String path, List<Warning> warnings) {
        Placeholders.Parsed parsed = Placeholders.parse(rawValue);
        if (!SecretKeys.isSecret(key)) {
            return new ConfigEntry(key, rawValue, line, false, false, parsed);
        }
        Placeholders.Parsed safe = Placeholders.redacted(parsed);
        if (parsed.hasLiteral()) {
            warnings.add(new Warning(WarningCodes.LITERAL_SECRET, key, path, line, LITERAL_SECRET_MESSAGE));
            return new ConfigEntry(key, null, line, true, true, safe);
        }
        return new ConfigEntry(key, rawValue, line, true, false, safe);
    }
}

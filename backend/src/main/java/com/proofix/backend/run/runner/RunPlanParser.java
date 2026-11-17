package com.proofix.backend.run.runner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.redaction.SecretRedactor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates a report-analysis body (schema v1) and builds the canonical plan
 * that is stored: {@code {schemaVersion, recipe, journeys, redactedFields}}.
 * Nothing from the request is stored verbatim; every field is checked by type
 * and limit, then rebuilt. Commands and evidence are untrusted text (the
 * server never executes them) and are run through SecretRedactor first;
 * redactedFields lists every field redaction changed.
 */
@Component
public class RunPlanParser {

    static final int SCHEMA_VERSION = 1;

    private static final Set<String> TOP_FIELDS = Set.of("schemaVersion", "recipe", "journeys");
    private static final Set<String> RECIPE_FIELDS = Set.of("stack", "buildCommand",
            "startCommand", "port", "envVarNames", "needsDatabase", "evidence", "javaVersion",
            "healthCheckPath");

    private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern HEALTH_PATH = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,199}");

    /** The key-name rule applies too, so "PASSWORD=x" in a command is caught. */
    private static final String REDACTION_PATH = "runner-plan.env";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SecretRedactor redactor;

    public RunPlanParser(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    /** The canonical JSON to store, and which fields redaction changed. */
    public record ParsedPlan(String json, List<String> redactedFields) {
    }

    public ParsedPlan parse(byte[] body) {
        JsonNode root = StrictJson.readObject(body, TOP_FIELDS);

        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isInt() || version.intValue() != SCHEMA_VERSION) {
            throw new BadRequestException("schemaVersion must be " + SCHEMA_VERSION);
        }
        if (!root.has("journeys") || !root.get("journeys").isNull()) {
            throw new BadRequestException("journeys must be null in schema version 1");
        }
        JsonNode recipe = root.get("recipe");
        if (recipe == null || !recipe.isObject()) {
            throw new BadRequestException("recipe must be an object");
        }
        StrictJson.rejectUnknown(recipe, RECIPE_FIELDS, "recipe");

        List<String> redacted = new ArrayList<>();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("stack", text(recipe, "stack", 50));
        out.put("buildCommand", redact(text(recipe, "buildCommand", 500), "recipe.buildCommand",
                redacted));
        out.put("startCommand", redact(text(recipe, "startCommand", 500), "recipe.startCommand",
                redacted));
        out.put("port", integer(recipe, "port", 1, 65535));
        ArrayNode env = out.putArray("envVarNames");
        envVarNames(recipe).forEach(env::add);
        putNullable(out, "needsDatabase", optionalText(recipe, "needsDatabase", 50));
        ArrayNode evidence = out.putArray("evidence");
        List<String> items = textArray(recipe, "evidence", 20, 200);
        for (int i = 0; i < items.size(); i++) {
            evidence.add(redact(items.get(i), "recipe.evidence[" + i + "]", redacted));
        }
        JsonNode java = recipe.get("javaVersion");
        if (java == null || java.isNull()) {
            out.putNull("javaVersion");
        } else {
            out.put("javaVersion", integer(recipe, "javaVersion", 8, 99));
        }
        putNullable(out, "healthCheckPath", healthCheckPath(recipe));

        ObjectNode plan = MAPPER.createObjectNode();
        plan.put("schemaVersion", SCHEMA_VERSION);
        plan.set("recipe", out);
        plan.putNull("journeys");
        ArrayNode redactedFields = plan.putArray("redactedFields");
        redacted.forEach(redactedFields::add);
        return new ParsedPlan(MAPPER.writeValueAsString(plan), List.copyOf(redacted));
    }

    // ------------------------------------------------------------------

    private String redact(String value, String field, List<String> redactedFields) {
        SecretRedactor.RedactionResult result = redactor.redact(REDACTION_PATH, value);
        if (result.redacted()) {
            redactedFields.add(field);
        }
        return result.content();
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /** Required string: 1..max characters, no control characters. */
    private static String text(JsonNode node, String field, int max) {
        String value = StrictJson.requiredString(node, field, max, null);
        rejectControl(value, field);
        return value;
    }

    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return text(node, field, max);
    }

    private static int integer(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new BadRequestException(field + " is required");
        }
        if (!value.isInt() || value.intValue() < min || value.intValue() > max) {
            throw new BadRequestException(field + " must be an integer from " + min + " to " + max);
        }
        return value.intValue();
    }

    private static List<String> textArray(JsonNode node, String field, int maxItems, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new BadRequestException(field + " is required");
        }
        if (!value.isArray() || value.size() > maxItems) {
            throw new BadRequestException(field + " must be a list of at most " + maxItems
                    + " strings");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString() || item.stringValue().isEmpty()
                    || item.stringValue().length() > maxLength) {
                throw new BadRequestException(field + " entries must be strings of 1 to "
                        + maxLength + " characters");
            }
            rejectControl(item.stringValue(), field);
            items.add(item.stringValue());
        }
        return items;
    }

    private static List<String> envVarNames(JsonNode recipe) {
        List<String> names = textArray(recipe, "envVarNames", 50, 100);
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!ENV_VAR_NAME.matcher(name).matches()) {
                throw new BadRequestException(
                        "envVarNames entries must match ^[A-Z][A-Z0-9_]*$ (names only, never values)");
            }
            if (!seen.add(name)) {
                throw new BadRequestException("envVarNames must not contain duplicates");
            }
        }
        return names;
    }

    private static String healthCheckPath(JsonNode recipe) {
        String path = optionalText(recipe, "healthCheckPath", 200);
        if (path == null) {
            return null;
        }
        if (!HEALTH_PATH.matcher(path).matches() || path.startsWith("//")
                || path.contains("/../") || path.endsWith("/..") || path.contains("..")) {
            throw new BadRequestException("healthCheckPath must be a path starting with / "
                    + "(no host, query, fragment, whitespace or '..')");
        }
        return path;
    }

    private static void rejectControl(String value, String field) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException(field + " must not contain control characters");
        }
    }
}

package com.proofix.backend.run.runner;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.proofix.backend.exception.BadRequestException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict JSON for runner request bodies: one JSON object, no duplicate keys,
 * nothing after it, and only the listed fields. Values are checked by type
 * explicitly (no coercion: "8080" is not a number, 5 is not a string).
 * Error messages never echo request values.
 */
final class StrictJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,40}");

    private StrictJson() {
    }

    static JsonNode readObject(byte[] body, Set<String> allowedFields) {
        if (body == null || body.length == 0) {
            throw new BadRequestException("Request body is required");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw new BadRequestException("Malformed JSON body");
        }
        if (node == null || !node.isObject()) {
            throw new BadRequestException("Request body must be a JSON object");
        }
        rejectUnknown(node, allowedFields, null);
        return node;
    }

    /** Rejects fields not in {@code allowed}; {@code path} prefixes the message. */
    static void rejectUnknown(JsonNode object, Set<String> allowed, String path) {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (!allowed.contains(field.getKey())) {
                String name = SAFE_NAME.matcher(field.getKey()).matches() ? field.getKey() : "?";
                throw new BadRequestException("Unknown field: "
                        + (path == null ? "" : path + ".") + name);
            }
        }
    }

    /** A required string field, at most {@code max} characters, matching {@code shape} if given. */
    static String requiredString(JsonNode object, String field, int max, Pattern shape) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw new BadRequestException(field + " is required");
        }
        if (!value.isString()) {
            throw new BadRequestException(field + " must be a string");
        }
        String text = value.stringValue();
        if (text.isEmpty() || text.length() > max) {
            throw new BadRequestException(field + " must be 1 to " + max + " characters");
        }
        if (shape != null && !shape.matcher(text).matches()) {
            throw new BadRequestException(field + " has an invalid format");
        }
        return text;
    }
}

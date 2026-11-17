package com.proofix.backend.run.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.redaction.SecretRedactor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every rejection class of the strict report schema (v1). */
class RunPlanParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RunPlanParser parser = new RunPlanParser(new SecretRedactor());

    /** Raw JSON values of a valid recipe, in order; tests replace or drop entries. */
    private static Map<String, String> recipe() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("stack", "\"java-spring-boot\"");
        r.put("buildCommand", "\"./mvnw package\"");
        r.put("startCommand", "\"java -jar app.jar\"");
        r.put("port", "8080");
        r.put("envVarNames", "[\"DB_URL\"]");
        r.put("needsDatabase", "\"postgres\"");
        r.put("evidence", "[\"pom.xml\"]");
        r.put("javaVersion", "21");
        r.put("healthCheckPath", "\"/actuator/health\"");
        return r;
    }

    private static String body(Map<String, String> recipe) {
        String fields = recipe.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
                .collect(Collectors.joining(", "));
        return "{\"schemaVersion\": 1, \"recipe\": {" + fields + "}, \"journeys\": null}";
    }

    private static String with(String field, String rawValue) {
        Map<String, String> r = recipe();
        r.put(field, rawValue);
        return body(r);
    }

    private static String without(String field) {
        Map<String, String> r = recipe();
        r.remove(field);
        return body(r);
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    private static String array(int n, String element) {
        return IntStream.range(0, n).mapToObj(i -> element.replace("#", String.valueOf(i)))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private RunPlanParser.ParsedPlan parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private void rejects(String json) {
        assertThrows(BadRequestException.class, () -> parse(json), json.length() > 300
                ? json.substring(0, 300) : json);
    }

    // ------------------------------------------------------------------
    // Accepted
    // ------------------------------------------------------------------

    @Test
    void validReportIsAcceptedAndCanonicalised() {
        RunPlanParser.ParsedPlan plan = parse(body(recipe()));

        JsonNode stored = JSON.readTree(plan.json());
        assertEquals(List.of("schemaVersion", "recipe", "journeys", "redactedFields"),
                new ArrayList<>(stored.propertyNames()));
        assertEquals(List.of("stack", "buildCommand", "startCommand", "port", "envVarNames",
                        "needsDatabase", "evidence", "javaVersion", "healthCheckPath"),
                new ArrayList<>(stored.get("recipe").propertyNames()));
        assertTrue(plan.redactedFields().isEmpty());
    }

    @Test
    void boundariesAreAccepted() {
        assertDoesNotThrow(() -> parse(with("port", "1")));
        assertDoesNotThrow(() -> parse(with("port", "65535")));
        assertDoesNotThrow(() -> parse(with("stack", quoted("s".repeat(50)))));
        assertDoesNotThrow(() -> parse(with("buildCommand", quoted("b".repeat(500)))));
        assertDoesNotThrow(() -> parse(with("startCommand", quoted("s".repeat(500)))));
        assertDoesNotThrow(() -> parse(with("envVarNames", array(50, "\"V#\""))));
        assertDoesNotThrow(() -> parse(with("envVarNames", "[\"" + "A".repeat(100) + "\"]")));
        assertDoesNotThrow(() -> parse(with("envVarNames", "[]")));
        assertDoesNotThrow(() -> parse(with("evidence", array(20, "\"file#\""))));
        assertDoesNotThrow(() -> parse(with("evidence", "[\"" + "e".repeat(200) + "\"]")));
        assertDoesNotThrow(() -> parse(with("evidence", "[]")));
        assertDoesNotThrow(() -> parse(with("needsDatabase", "null")));
        assertDoesNotThrow(() -> parse(with("needsDatabase", quoted("d".repeat(50)))));
        assertDoesNotThrow(() -> parse(with("javaVersion", "8")));
        assertDoesNotThrow(() -> parse(with("javaVersion", "99")));
        assertDoesNotThrow(() -> parse(with("javaVersion", "null")));
        assertDoesNotThrow(() -> parse(with("healthCheckPath", "\"/\"")));
        assertDoesNotThrow(() -> parse(with("healthCheckPath", quoted("/" + "h".repeat(199)))));
        assertDoesNotThrow(() -> parse(with("healthCheckPath", "\"/api/v1/health:ready;x=1\"")));
        assertDoesNotThrow(() -> parse(with("healthCheckPath", "null")));
        assertDoesNotThrow(() -> parse(without("needsDatabase")));
        assertDoesNotThrow(() -> parse(without("javaVersion")));
        assertDoesNotThrow(() -> parse(without("healthCheckPath")));
    }

    // ------------------------------------------------------------------
    // Envelope
    // ------------------------------------------------------------------

    @Test
    void envelopeRejections() {
        String recipe = body(recipe()).substring(body(recipe()).indexOf("\"recipe\""),
                body(recipe()).indexOf(", \"journeys\""));
        for (String bad : List.of(
                "",
                "null",
                "[]",
                "\"text\"",
                "{}",
                "{\"schemaVersion\": 1, " + recipe + "}",                                // no journeys
                "{" + recipe + ", \"journeys\": null}",                                   // no version
                "{\"schemaVersion\": 2, " + recipe + ", \"journeys\": null}",
                "{\"schemaVersion\": 0, " + recipe + ", \"journeys\": null}",
                "{\"schemaVersion\": \"1\", " + recipe + ", \"journeys\": null}",
                "{\"schemaVersion\": 1.0, " + recipe + ", \"journeys\": null}",
                "{\"schemaVersion\": null, " + recipe + ", \"journeys\": null}",
                "{\"schemaVersion\": 1, " + recipe + ", \"journeys\": []}",
                "{\"schemaVersion\": 1, " + recipe + ", \"journeys\": {}}",
                "{\"schemaVersion\": 1, " + recipe + ", \"journeys\": \"x\"}",
                "{\"schemaVersion\": 1, \"recipe\": null, \"journeys\": null}",
                "{\"schemaVersion\": 1, \"recipe\": [], \"journeys\": null}",
                "{\"schemaVersion\": 1, \"journeys\": null}",
                "{\"schemaVersion\": 1, " + recipe + ", \"journeys\": null, \"extra\": 1}",
                "{\"schemaVersion\": 1, " + recipe + ", \"journeys\": null, \"redactedFields\": []}",
                "{\"schemaVersion\": 1, \"schemaVersion\": 1, " + recipe + ", \"journeys\": null}",
                body(recipe()) + " {}",
                body(recipe()).substring(0, body(recipe()).length() - 1))) {
            rejects(bad);
        }
    }

    // ------------------------------------------------------------------
    // Recipe fields
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"stack", "buildCommand", "startCommand", "port", "envVarNames",
            "evidence"})
    void requiredFieldsMustBePresent(String field) {
        rejects(without(field));
        rejects(with(field, "null"));
    }

    @Test
    void extraOrDuplicateRecipeFieldsAreRejected() {
        rejects(with("extra", "1"));
        rejects(with("redactedFields", "[]"));
        rejects(with("envVarValues", "{\"DB_URL\": \"x\"}"));
        rejects(body(recipe()).replace("\"stack\": \"java-spring-boot\"",
                "\"stack\": \"a\", \"stack\": \"b\""));
    }

    @Test
    void stringFieldRejections() {
        for (String field : List.of("stack", "buildCommand", "startCommand")) {
            rejects(with(field, "\"\""));
            rejects(with(field, "5"));
            rejects(with(field, "[\"x\"]"));
            rejects(with(field, "{}"));
            rejects(with(field, "true"));
            rejects(with(field, quoted("a\\nb")));
            rejects(with(field, quoted("a\\tb")));
            rejects(with(field, quoted("a\\u0000b")));
            rejects(with(field, quoted("a\\u007fb")));
        }
        rejects(with("stack", quoted("s".repeat(51))));
        rejects(with("buildCommand", quoted("b".repeat(501))));
        rejects(with("startCommand", quoted("s".repeat(501))));
        rejects(with("needsDatabase", quoted("d".repeat(51))));
        rejects(with("needsDatabase", "\"\""));
        rejects(with("needsDatabase", "true"));
        rejects(with("needsDatabase", quoted("a\\nb")));
    }

    @Test
    void portRejections() {
        for (String port : List.of("0", "65536", "-1", "\"8080\"", "80.5", "8080.0", "1e3",
                "99999999999", "[8080]", "true")) {
            rejects(with("port", port));
        }
    }

    @Test
    void envVarNameRejections() {
        for (String names : List.of(
                "[\"db_url\"]", "[\"1ABC\"]", "[\"A-B\"]", "[\"A B\"]", "[\"\"]", "[\"_A\"]",
                "[\"Ä\"]", "[5]", "[null]", "[[\"A\"]]", "\"DB_URL\"", "{}",
                "[\"DB_URL\", \"DB_URL\"]",
                "[\"" + "A".repeat(101) + "\"]",
                "[\"DB_URL=postgres://x\"]",
                array(51, "\"V#\""))) {
            rejects(with("envVarNames", names));
        }
    }

    @Test
    void evidenceRejections() {
        for (String evidence : List.of(
                array(21, "\"f#\""), "[\"" + "e".repeat(201) + "\"]", "[\"\"]", "[5]", "[null]",
                "[\"a\\nb\"]", "\"pom.xml\"", "{}", "[[\"x\"]]")) {
            rejects(with("evidence", evidence));
        }
    }

    @Test
    void javaVersionRejections() {
        for (String v : List.of("7", "100", "0", "-21", "\"21\"", "21.0", "17.5", "[21]")) {
            rejects(with("javaVersion", v));
        }
    }

    @Test
    void healthCheckPathRejections() {
        for (String path : List.of(
                "\"\"", "\"health\"", "\"http://evil.example/health\"", "\"//evil.example\"",
                "\"/a?b=1\"", "\"/a#b\"", "\"/a b\"", "\"/../etc\"", "\"/a/../b\"", "\"/a\\\\b\"",
                "\"/a\\nb\"", "\"/é\"", "\"/a<b\"", "\"/a\\\"b\"",
                quoted("/" + "h".repeat(200)), "5", "[\"/\"]")) {
            rejects(with("healthCheckPath", path));
        }
    }

    // ------------------------------------------------------------------
    // Redaction
    // ------------------------------------------------------------------

    @Test
    void redactionChangesAreListed() {
        String token = "ghp_" + "B".repeat(36);
        Map<String, String> r = recipe();
        r.put("startCommand", quoted("API_TOKEN=s3cr3tvalue java -jar app.jar"));
        r.put("evidence", "[\"pom.xml\", \"found " + token + "\"]");
        RunPlanParser.ParsedPlan plan = parse(body(r));

        assertEquals(List.of("recipe.startCommand", "recipe.evidence[1]"), plan.redactedFields());
        assertTrue(!plan.json().contains("s3cr3tvalue") && !plan.json().contains(token));
        JsonNode stored = JSON.readTree(plan.json());
        assertEquals(2, stored.get("redactedFields").size());
    }

    @Test
    void errorMessagesNeverEchoValues() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> parse(with("stack", quoted("SECRETVALUE" + "x".repeat(60)))));
        assertTrue(!e.getMessage().contains("SECRETVALUE"), e.getMessage());
        e = assertThrows(BadRequestException.class, () -> parse(with("SECRET_FIELD_NAME_" + "x".repeat(40), "1")));
        assertTrue(!e.getMessage().contains("SECRET_FIELD_NAME"), e.getMessage());
    }
}

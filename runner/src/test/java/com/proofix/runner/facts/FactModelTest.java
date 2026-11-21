package com.proofix.runner.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofix.runner.facts.model.EnvVarFact;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.facts.model.Facts;
import com.proofix.runner.facts.model.ValueWithEvidence;
import com.proofix.runner.scan.model.Warning;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shape, ordering and invariants of the schemaVersion 2 fact model and the extended Warning. */
class FactModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(Object o) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(o));
    }

    private static List<String> fields(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static FactEvidence ev(String path, Integer line, String rule) {
        return new FactEvidence(rule, path, line, null, null);
    }

    private static Fact known(String module, String name, String profile, String value) {
        return new Fact(name, value, FactStatus.KNOWN, Fact.VERIFIED, module, profile, null,
                List.of(ev("pom.xml", 1, "r")), null, null, null);
    }

    @Test
    void warningWithoutKeyOrLineKeepsTheV1Shape() throws Exception {
        JsonNode w = json(new Warning("FILE_TOO_LARGE", null, "too large"));
        assertEquals(List.of("code", "path", "message"), fields(w));
        assertTrue(w.get("path").isNull());
    }

    @Test
    void warningWithKeyAndLine() throws Exception {
        JsonNode w = json(new Warning("LITERAL_SECRET", "admin.password", "a/application.yml", 7, "m"));
        assertEquals(List.of("code", "key", "path", "line", "message"), fields(w));
        assertEquals(7, w.get("line").asInt());
    }

    @Test
    void factFieldOrderAndAlwaysPresentArrays() throws Exception {
        JsonNode f = json(known("api", "java.version", null, "21"));
        assertEquals(List.of("name", "value", "status", "label", "module", "profile",
                "evidence", "overridden", "candidates", "reason"), fields(f));
        assertEquals("KNOWN", f.get("status").asText());
        assertEquals("VERIFIED", f.get("label").asText());
        assertTrue(f.get("overridden").isArray());
        assertTrue(f.get("candidates").isArray());
        assertTrue(f.get("profile").isNull());
        assertTrue(f.get("reason").isNull());
        assertEquals(List.of("rule", "path", "line", "commitSha", "sha256"), fields(f.get("evidence").get(0)));
    }

    @Test
    void envVarIsOnlyPresentWhenSet() throws Exception {
        Fact port = new Fact("server.port", "8080", FactStatus.DEFAULTED, Fact.VERIFIED, "api", "default",
                "PORT", List.of(), null, null, "placeholder default");
        assertEquals("PORT", json(port).get("envVar").asText());
        assertFalse(json(known("api", "x", null, "1")).has("envVar"));
    }

    @Test
    void envVarFactShape() throws Exception {
        JsonNode e = json(new EnvVarFact("env.PORT", false, "8080", List.of(ev("a.yml", 2, "r"))));
        assertEquals(List.of("name", "required", "default", "evidence", "reason"), fields(e));
        assertTrue(e.get("reason").isNull());
        assertTrue(json(new EnvVarFact("env.DB_PASSWORD", true, null, List.of())).get("default").isNull());
        assertThrows(IllegalArgumentException.class, () -> new EnvVarFact("env.X", true, "1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EnvVarFact("PORT", false, null, List.of()));
    }

    @Test
    void invariants() {
        assertThrows(IllegalArgumentException.class, () -> new Fact("x", null, FactStatus.UNRESOLVED,
                Fact.VERIFIED, ".", null, null, List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Fact("x", null, FactStatus.DEFAULTED,
                Fact.VERIFIED, ".", null, null, List.of(), null, null, null));
        List<ValueWithEvidence> two = List.of(new ValueWithEvidence("17", List.of()),
                new ValueWithEvidence("21", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Fact("x", "21", FactStatus.KNOWN,
                Fact.VERIFIED, ".", null, null, List.of(), null, two, null));
        assertThrows(IllegalArgumentException.class, () -> new Fact("x", null, FactStatus.CONFLICT,
                Fact.VERIFIED, ".", null, null, List.of(), null, two.subList(0, 1), "r"));
        new Fact("x", null, FactStatus.CONFLICT, Fact.VERIFIED, ".", null, null, List.of(), null, two, "r");
        assertThrows(IllegalArgumentException.class, () -> ev("p", 0, "r"));
    }

    @Test
    void evidenceSortsByPathLineRule() {
        Fact f = new Fact("x", "1", FactStatus.KNOWN, Fact.VERIFIED, ".", null, null,
                List.of(ev("b", 1, "a"), ev("a", 5, "z"), ev("a", 5, "b"), ev("a", null, "q")),
                null, null, null);
        assertEquals(List.of(ev("a", null, "q"), ev("a", 5, "b"), ev("a", 5, "z"), ev("b", 1, "a")), f.evidence());
    }

    @Test
    void factsSortByModuleNameProfileValue() {
        List<FactEntry> in = List.of(
                known("common", "dependency", null, "g:b"),
                known("api", "server.port", "prod", "9090"),
                known("api", "server.port", "default", "8080"),
                new EnvVarFact("env.PORT", false, "8080", List.of()),
                known("api", "dependency", null, "g:b"),
                known("api", "dependency", null, "g:a"));
        Facts facts = new Facts(Facts.SCHEMA_VERSION, new Facts.Repo(null, false, "walk"),
                new Facts.Scan(0, List.of(), List.of()), List.of(), null, in, List.of());
        List<String> order = facts.facts().stream().map(e -> e instanceof Fact f
                ? f.module() + "|" + f.name() + "|" + f.profile() + "|" + f.value() : e.name()).toList();
        assertEquals(List.of("env.PORT",
                "api|dependency|null|g:a", "api|dependency|null|g:b",
                "api|server.port|default|8080", "api|server.port|prod|9090",
                "common|dependency|null|g:b"), order);
        assertEquals(2, Facts.SCHEMA_VERSION);
    }
}

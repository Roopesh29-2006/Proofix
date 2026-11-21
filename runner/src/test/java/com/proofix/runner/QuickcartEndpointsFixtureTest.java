package com.proofix.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.proofix.runner.cli.Cli;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import com.proofix.runner.testutil.TestGit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end over the quickcart-endpoints fixture, through the real CLI (schemaVersion 3). */
class QuickcartEndpointsFixtureTest {

    static final String API = "api/src/main/java/com/quickcart/api/";
    static final String YML = "api/src/main/resources/application.yml";
    static final String PATHS = "common/src/main/java/com/quickcart/common/Paths.java";
    static final String BROKEN = API + "Broken.java";

    @TempDir
    Path tmp;

    String canary;
    Outcome out;

    /** Copies the fixture and swaps the committed placeholder in Broken.java for a random canary. */
    static Path load(Path tmp, String name, String canary) {
        Path repo = Fixtures.load("quickcart-endpoints", tmp.resolve(name));
        try {
            Path broken = repo.resolve(BROKEN);
            String s = Files.readString(broken);
            assertTrue(s.contains("__CANARY__"));
            Files.writeString(broken, s.replace("__CANARY__", canary));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return repo;
    }

    @BeforeEach
    void scan() {
        canary = "SRC" + UUID.randomUUID().toString().replace("-", "");
        out = RunnerHarness.scan(load(tmp, "a", canary), tmp.resolve("out"));
        assertEquals(0, out.exit(), out.stderr());
    }

    private List<JsonNode> endpoints() {
        List<JsonNode> l = new ArrayList<>();
        out.json().get("endpoints").forEach(l::add);
        return l;
    }

    private JsonNode endpoint(String method, String path) {
        JsonNode hit = null;
        for (JsonNode e : endpoints()) {
            if (e.get("method").asText().equals(method) && path.equals(e.get("path").textValue())) {
                if (hit != null) throw new AssertionError("duplicate " + method + " " + path);
                hit = e;
            }
        }
        assertNotNull(hit, method + " " + path);
        return hit;
    }

    private static boolean cites(JsonNode node, String rule, String path, int line) {
        for (JsonNode ev : node.get("evidence")) {
            if (ev.get("rule").asText().equals(rule) && ev.get("path").asText().equals(path)
                    && ev.get("line").asInt() == line) {
                return true;
            }
        }
        return false;
    }

    private JsonNode fact(String name) {
        for (JsonNode f : out.json().get("facts")) if (f.get("name").asText().equals(name)) return f;
        throw new AssertionError("no " + name);
    }

    private List<JsonNode> warnings(String code) {
        List<JsonNode> l = new ArrayList<>();
        for (JsonNode w : out.json().get("warnings")) if (w.get("code").asText().equals(code)) l.add(w);
        return l;
    }

    @Test
    void schemaAndOrder() {
        JsonNode j = out.json();
        assertEquals(3, j.get("schemaVersion").asInt());
        List<String> keys = new ArrayList<>();
        j.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("schemaVersion", "repo", "scan", "stacks", "testTarget", "facts", "endpoints",
                "endpointsComplete", "warnings"), keys);
        List<String> order = endpoints().stream()
                .map(e -> e.get("path").asText("null") + " " + e.get("method").asText()).toList();
        assertEquals(List.of(
                "/shop/a GET", "/shop/a POST",
                "/shop/api/orders POST",
                "/shop/api/orders/{id:\\d+}/items GET", // ':' sorts before '}'
                "/shop/api/orders/{id} GET",
                "/shop/b GET", "/shop/b POST",
                "/shop/orders GET",
                "/shop/x ANY",
                "null GET"), order);
        List<String> fields = new ArrayList<>();
        endpoints().get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("method", "path", "pathVariables", "handler", "module", "status", "reason", "rawPath",
                "evidence"), fields);
    }

    @Test
    void orderControllerWithTrailingSlashAndContextPath() {
        JsonNode get = endpoint("GET", "/shop/api/orders/{id}");
        assertEquals("com.quickcart.api.OrderController.getOrder", get.get("handler").asText());
        assertEquals("api", get.get("module").asText());
        assertEquals("KNOWN", get.get("status").asText());
        assertTrue(get.get("reason").isNull());
        assertTrue(get.get("rawPath").isNull());
        assertEquals("id", get.get("pathVariables").get(0).asText());
        assertTrue(cites(get, "controller-class", API + "OrderController.java", 10));
        assertTrue(cites(get, "handler-mapping", API + "OrderController.java", 13));
        assertTrue(cites(get, "context-path", YML, 3));

        JsonNode post = endpoint("POST", "/shop/api/orders");
        assertEquals("com.quickcart.api.OrderController.createOrder", post.get("handler").asText());
        assertTrue(cites(post, "handler-mapping", API + "OrderController.java", 18));

        JsonNode items = endpoint("GET", "/shop/api/orders/{id:\\d+}/items");
        assertEquals(1, items.get("pathVariables").size());
        assertEquals("id", items.get("pathVariables").get(0).asText());
    }

    @Test
    void controllerNotNamedControllerConstantsArraysAndAny() {
        JsonNode list = endpoint("GET", "/shop/orders");
        assertEquals("com.quickcart.api.OrderApi.list", list.get("handler").asText());
        assertTrue(cites(list, "controller-class", API + "OrderApi.java", 13));
        assertTrue(cites(list, "handler-mapping", API + "OrderApi.java", 16));
        assertTrue(cites(list, "path-constant", PATHS, 6));

        for (String p : List.of("/shop/a", "/shop/b")) {
            for (String m : List.of("GET", "POST")) {
                assertEquals("com.quickcart.api.OrderApi.multi", endpoint(m, p).get("handler").asText());
            }
        }
        assertEquals("com.quickcart.api.OrderApi.any", endpoint("ANY", "/shop/x").get("handler").asText());

        JsonNode dyn = endpoints().get(endpoints().size() - 1);
        assertTrue(dyn.get("path").isNull());
        assertEquals("UNRESOLVED", dyn.get("status").asText());
        assertEquals("buildPath()", dyn.get("rawPath").asText());
        assertEquals("path is not a literal or resolvable constant", dyn.get("reason").asText());
        assertEquals("com.quickcart.api.OrderApi.dynamic", dyn.get("handler").asText());
    }

    @Test
    void entryPointIgnoresTestSources() {
        JsonNode e = fact("entry.mainClass");
        assertEquals("com.quickcart.api.QuickcartApplication", e.get("value").asText());
        assertEquals("KNOWN", e.get("status").asText());
        assertTrue(cites(e, "spring-boot-application-annotation", API + "QuickcartApplication.java", 6));
        assertEquals(1, e.get("evidence").size());
        assertTrue(endpoints().stream().noneMatch(x -> x.get("path").asText("").contains("test-only")));
    }

    @Test
    void envFactsFromCode() {
        JsonNode stripe = fact("env.STRIPE_KEY");
        assertTrue(stripe.get("required").asBoolean());
        assertTrue(cites(stripe, "value-annotation", API + "Keys.java", 6));
        JsonNode db = fact("env.DB_PASSWORD");
        assertFalse(db.get("required").asBoolean());
        assertTrue(db.get("default").isNull());
        assertEquals("read via System.getenv; the code may handle absence", db.get("reason").asText());
        assertTrue(cites(db, "system-getenv", API + "Keys.java", 9));
        long envFacts = 0;
        for (JsonNode f : out.json().get("facts")) if (f.get("name").asText().startsWith("env.")) envFacts++;
        assertEquals(2, envFacts);
        List<JsonNode> computed = warnings("ENV_NAME_COMPUTED");
        assertEquals(1, computed.size());
        assertEquals(API + "Keys.java", computed.get(0).get("path").asText());
        assertEquals(12, computed.get(0).get("line").asInt());
    }

    @Test
    void brokenFileAndRouterFunctionMakeTheListIncomplete() {
        assertFalse(out.json().get("endpointsComplete").asBoolean());
        List<JsonNode> broken = warnings("SOURCE_UNPARSEABLE");
        assertEquals(1, broken.size());
        assertEquals(BROKEN, broken.get(0).get("path").asText());
        assertEquals(11, broken.get(0).get("line").asInt());
        assertTrue(out.hasWarning("ENDPOINTS_NOT_COVERED", API + "Routes.java"));
        assertTrue(endpoints().stream().noneMatch(x -> x.get("path").asText("").contains("broken")));
    }

    @Test
    void canaryInBrokenSourceNeverAppears() {
        assertFalse(out.everything().contains(canary));
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int exit = new Cli().run(new String[] {"scan", load(tmp, "c", canary).toString()},
                new PrintStream(so, true, StandardCharsets.UTF_8), new PrintStream(se, true, StandardCharsets.UTF_8));
        assertEquals(0, exit);
        assertFalse(so.toString(StandardCharsets.UTF_8).contains(canary));
        assertFalse(se.toString(StandardCharsets.UTF_8).contains(canary));
        assertTrue(se.toString(StandardCharsets.UTF_8).contains("10 endpoint(s) (incomplete)"));
    }

    @Test
    void deterministic() {
        Outcome again = RunnerHarness.scan(load(tmp, "e", canary), tmp.resolve("out-e"));
        assertArrayEquals(out.bytes(), again.bytes());
    }

    @Test
    void gitModeCitesTheCommitAndStaysDeterministic() {
        TestGit.requireGit();
        Path repo = load(tmp, "g", canary);
        TestGit.initAndCommitAll(repo);
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out-g"));
        String sha = o.json().get("repo").get("commitSha").asText();
        JsonNode ev = o.json().get("endpoints").get(0).get("evidence").get(0);
        assertEquals(sha, ev.get("commitSha").asText());
        assertEquals(64, ev.get("sha256").asText().length());
        assertFalse(o.everything().contains(canary));
        assertArrayEquals(o.bytes(), RunnerHarness.scan(repo, tmp.resolve("out-g2")).bytes());
    }
}

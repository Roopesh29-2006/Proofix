package com.proofix.runner.config.spring;

import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [SEC] YAML and .properties readers: lines, profiles, secret dropping, limits, malformed input. */
class ConfigReadersTest {

    private static final String YML = "src/main/resources/application.yml";
    private static final String PROPS = "src/main/resources/application.properties";

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ConfigDocs yaml(String s) {
        return YamlConfigReader.read(YML, b(s), "default");
    }

    private static ConfigDocs props(String s) {
        return PropertiesConfigReader.read(PROPS, b(s), "default");
    }

    private static ConfigEntry e(ConfigDocs d, int doc, String key) {
        return d.docs().get(doc).entries().get(key);
    }

    private static void assertUnparseable(ConfigDocs d, String path, String message) {
        assertTrue(d.docs().isEmpty());
        assertEquals(List.of(new Warning(WarningCodes.CONFIG_UNPARSEABLE, path, message)), d.warnings());
    }

    private static String canary() {
        return "CANARY-" + UUID.randomUUID();
    }

    // --- YAML -------------------------------------------------------------------------------

    @Test
    void yamlFlattensWithLines() {
        ConfigDocs d = yaml("server:\n  port: ${PORT:8080}\n  servlet:\n    context-path: /api\nlist:\n  - a\n  - b\nempty:\n");
        assertEquals("${PORT:8080}", e(d, 0, "server.port").value());
        assertEquals(2, e(d, 0, "server.port").line());
        assertEquals("/api", e(d, 0, "server.servlet.context-path").value());
        assertEquals(4, e(d, 0, "server.servlet.context-path").line());
        assertEquals("b", e(d, 0, "list[1]").value());
        assertEquals("", e(d, 0, "empty").value());
        assertEquals("default", d.docs().get(0).profile());
    }

    @Test
    void yamlDocumentsWithOnProfile() {
        ConfigDocs d = yaml("server.port: 8080\n---\nspring:\n  config:\n    activate:\n      on-profile: prod\nserver:\n  port: 9090\n");
        assertEquals(2, d.docs().size());
        assertEquals("prod", d.docs().get(1).profile());
        assertEquals("9090", e(d, 1, "server.port").value());
        assertEquals(8, e(d, 1, "server.port").line());
        assertNull(e(d, 1, ConfigDocs.ON_PROFILE));
    }

    @Test
    void profileFromFileName() {
        assertEquals("prod", ConfigDocs.fileProfile("application-prod.yml"));
        assertEquals("default", ConfigDocs.fileProfile("application.properties"));
    }

    @Test
    void yamlLiteralSecretIsDroppedAndWarned() {
        String c = canary();
        ConfigDocs d = yaml("admin:\n  password: " + c + "\nstripe:\n  api-key: ${STRIPE_KEY}\n"
                + "db:\n  password: ${DB_PASSWORD:" + c + "}\n");
        ConfigEntry admin = e(d, 0, "admin.password");
        assertNull(admin.value());
        assertTrue(admin.secretLiteral());
        ConfigEntry stripe = e(d, 0, "stripe.api-key");
        assertEquals("${STRIPE_KEY}", stripe.value());
        assertFalse(stripe.secretLiteral());
        ConfigEntry db = e(d, 0, "db.password");
        assertNull(db.value(), "a non-empty default on a secret key is a committed secret");
        assertTrue(db.placeholders().get(0).hasDefault());
        assertNull(db.placeholders().get(0).defaultValue());
        assertEquals(List.of(
                new Warning(WarningCodes.LITERAL_SECRET, "admin.password", YML, 2, ConfigEntry.LITERAL_SECRET_MESSAGE),
                new Warning(WarningCodes.LITERAL_SECRET, "db.password", YML, 6, ConfigEntry.LITERAL_SECRET_MESSAGE)),
                d.warnings());
        assertFalse(d.toString().contains(c), "canary must not survive the reader");
    }

    @Test
    void malformedYamlNeverQuotesContent() {
        String c = canary();
        ConfigDocs d = yaml("a: [" + c + "\nb: {");
        assertUnparseable(d, YML, ConfigDocs.UNPARSEABLE);
        assertFalse(d.toString().contains(c));
    }

    @Test
    void yamlAliasBombIsBounded() {
        String bomb = """
                a: &a ["x","x","x","x","x","x","x","x","x"]
                b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
                c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]
                d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]
                e: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d]
                f: &f [*e,*e,*e,*e,*e,*e,*e,*e,*e]
                g: &g [*f,*f,*f,*f,*f,*f,*f,*f,*f]
                """;
        ConfigDocs d = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> yaml(bomb));
        assertUnparseable(d, YML, ConfigDocs.UNPARSEABLE);
    }

    @Test
    void scalarAliasFanOutHitsTheEntryLimit() {
        StringBuilder sb = new StringBuilder("v: &v x\nl:\n");
        for (int i = 0; i < ConfigDocs.MAX_ENTRIES + 5; i++) sb.append("  - *v\n");
        ConfigDocs d = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> yaml(sb.toString()));
        assertUnparseable(d, YML, ConfigDocs.TOO_MANY);
    }

    @Test
    void yamlNestingDepthIsLimited() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < YamlConfigReader.MAX_DEPTH + 5; i++) sb.append("  ".repeat(i)).append("k").append(i).append(":\n");
        sb.append("  ".repeat(YamlConfigReader.MAX_DEPTH + 5)).append("x\n");
        assertUnparseable(yaml(sb.toString()), YML, ConfigDocs.UNPARSEABLE);
        assertUnparseable(yaml("[".repeat(200) + "]".repeat(200)), YML, ConfigDocs.UNPARSEABLE);
    }

    @Test
    void yamlRecursiveAliasAndDuplicateKeysAreRefused() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertUnparseable(yaml("a: &x\n  b: *x\n"), YML, ConfigDocs.UNPARSEABLE));
        assertUnparseable(yaml("server:\n  port: 1\n  port: 2\n"), YML, ConfigDocs.UNPARSEABLE);
    }

    @Test
    void yamlGlobalTagsAreRefusedAndNeverConstructed() {
        // Compose never instantiates tags, and SnakeYAML 2.x's default TagInspector refuses global
        // tags at compose time, so this gadget is a plain parse failure.
        ConfigDocs d = yaml("x: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1/\"]]]]\n");
        assertUnparseable(d, YML, ConfigDocs.UNPARSEABLE);
        // Standard tags still compose.
        assertEquals("5", e(yaml("x: !!int 5\n"), 0, "x").value());
    }

    // --- .properties ------------------------------------------------------------------------

    @Test
    void propertiesSeparatorsCommentsContinuationsAndEscapes() {
        ConfigDocs d = props("# comment\n! also\nserver.port=8080\nserver.servlet.context-path : /api\n"
                + "a.b   value with spaces\nlong=one \\\n    two\nesc=tab\\there\\u0041\\=\nkey\\ with\\:colon=1\nempty=\n");
        assertEquals("8080", e(d, 0, "server.port").value());
        assertEquals(3, e(d, 0, "server.port").line());
        assertEquals("/api", e(d, 0, "server.servlet.context-path").value());
        assertEquals("value with spaces", e(d, 0, "a.b").value());
        assertEquals("one two", e(d, 0, "long").value());
        assertEquals(6, e(d, 0, "long").line(), "first physical line");
        assertEquals("tab\thereA=", e(d, 0, "esc").value());
        assertEquals("1", e(d, 0, "key with:colon").value());
        assertEquals("", e(d, 0, "empty").value());
    }

    @Test
    void propertiesDocumentsAndLastDuplicateWins() {
        ConfigDocs d = props("server.port=1\nserver.port=2\n#---\nspring.config.activate.on-profile=prod\nserver.port=9090\n");
        assertEquals("2", e(d, 0, "server.port").value());
        assertEquals(2, e(d, 0, "server.port").line());
        assertEquals("prod", d.docs().get(1).profile());
        assertEquals(5, e(d, 1, "server.port").line());
    }

    @Test
    void propertiesLiteralSecretIsDropped() {
        String c = canary();
        ConfigDocs d = props("spring.datasource.password=" + c + "\napp.token=${TOKEN}\n");
        assertNull(e(d, 0, "spring.datasource.password").value());
        assertEquals("${TOKEN}", e(d, 0, "app.token").value());
        assertEquals(1, d.warnings().size());
        assertEquals("spring.datasource.password", d.warnings().get(0).key());
        assertEquals(1, d.warnings().get(0).line());
        assertFalse(d.toString().contains(c));
    }

    @Test
    void malformedUnicodeEscapeIsUnparseable() {
        String c = canary();
        ConfigDocs d = props("k=" + c + "\\u12\n");
        assertUnparseable(d, PROPS, ConfigDocs.UNPARSEABLE);
        assertFalse(d.toString().contains(c));
    }

    @Test
    void propertiesEntryLimit() {
        assertUnparseable(props("k=v\n".repeat(ConfigDocs.MAX_ENTRIES + 1)), PROPS, ConfigDocs.TOO_MANY);
    }

    // --- placeholders and secret names ------------------------------------------------------

    @Test
    void placeholderParsing() {
        Placeholders.Parsed p = Placeholders.parse("${PORT:8080}");
        assertTrue(p.pure());
        assertEquals(new Placeholders.Placeholder("PORT", true, "8080"), p.placeholders().get(0));
        assertEquals("8080", p.withDefaults());

        Placeholders.Parsed url = Placeholders.parse("jdbc:postgresql://${DB_HOST:localhost}:5432/${DB}");
        assertFalse(url.pure());
        assertEquals(2, url.placeholders().size());
        assertNull(url.withDefaults(), "DB has no default");

        assertEquals("a:${B}", Placeholders.parse("${A:a:${B}}").placeholders().get(0).defaultValue());
        assertFalse(Placeholders.parse("${app.name}").placeholders().get(0).envStyle());
        assertTrue(Placeholders.parse("${unclosed").placeholders().isEmpty());
        assertFalse(Placeholders.parse("${A}${B}").hasLiteral());
        assertFalse(Placeholders.parse("${A:}").hasLiteral());
    }

    @Test
    void secretNames() {
        for (String k : new String[] {"spring.datasource.password", "x.PASSWD", "jwt.secret", "auth.token",
                "stripe.api-key", "x.apikey", "x.api_key", "ssh.private-key", "aws.credentials", "DB_PASSWORD"}) {
            assertTrue(SecretKeys.isSecret(k), k);
        }
        for (String k : new String[] {"server.port", "spring.datasource.url", "stripe.key", "STRIPE_KEY"}) {
            assertFalse(SecretKeys.isSecret(k), k);
        }
    }
}

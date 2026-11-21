package com.proofix.runner.source;

import com.proofix.runner.config.spring.SpringConfigFacts;
import com.proofix.runner.facts.model.EnvVarFact;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;
import com.proofix.runner.testutil.ConfigHarness;
import com.proofix.runner.testutil.ConfigHarness.Run;
import com.proofix.runner.testutil.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.proofix.runner.testutil.ConfigHarness.cites;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Env var names from @Value and System.getenv, merged into the config env facts. */
class CodeEnvTest {

    @TempDir
    Path tmp;

    private static final String J = "src/main/java/com/q/";

    private Run run(String... files) {
        Path r = tmp.resolve("repo-" + UUID.randomUUID());
        Fixtures.write(r, "pom.xml", EndpointExtractorTest.pom(true));
        for (int i = 0; i < files.length; i += 2) Fixtures.write(r, files[i], files[i + 1]);
        return ConfigHarness.run(r);
    }

    @Test
    void valueAndGetenvGiveEnvFactsWithCodeEvidence() {
        Run r = run(J + "Keys.java", """
                package com.q;
                class Keys {
                  @Value("${STRIPE_KEY}") String stripe;
                  String db = System.getenv("DB_PASSWORD");
                  String computed = System.getenv(prefix + "_KEY");
                  @Value("${spring.application.name}") String notEnv;
                  @Value("#{'${SPEL_ONLY}'}") String spel;
                  String lower = System.getenv("lower_case");
                }
                """);
        EnvVarFact stripe = r.env("STRIPE_KEY");
        assertTrue(stripe.required());
        assertNull(stripe.reason());
        assertTrue(cites(stripe.evidence(), SpringConfigFacts.RULE_VALUE, J + "Keys.java", 3));

        EnvVarFact db = r.env("DB_PASSWORD");
        assertFalse(db.required());
        assertNull(db.defaultValue());
        assertEquals("read via System.getenv; the code may handle absence", db.reason());
        assertTrue(cites(db.evidence(), SpringConfigFacts.RULE_GETENV, J + "Keys.java", 4));

        List<Warning> w = r.warnings(WarningCodes.ENV_NAME_COMPUTED);
        assertEquals(1, w.size());
        assertEquals(J + "Keys.java", w.get(0).path());
        assertEquals(5, w.get(0).line());

        assertNull(r.env("spring.application.name"));
        assertNull(r.env("SPEL_ONLY"));
        assertNull(r.env("lower_case"));
        assertEquals(2, r.facts().stream().filter(e -> e instanceof EnvVarFact).count());
    }

    @Test
    void mergesWithConfigFactsAndKeepsRequired() {
        Run r = run(
                "src/main/resources/application.yml", "a:\n  b: ${DB_PASSWORD}\n  port: ${PORT:8080}\n",
                J + "C.java", """
                        package com.q;
                        class C {
                          String p = System.getenv("DB_PASSWORD");
                          @Value("${PORT:8080}") String port;
                          @Value("${API_TOKEN:dev-token}") String token;
                        }
                        """);
        EnvVarFact db = r.env("DB_PASSWORD");
        assertTrue(db.required(), "getenv never lowers required");
        assertNull(db.reason());
        assertTrue(cites(db.evidence(), SpringConfigFacts.RULE_ENV, "src/main/resources/application.yml", 2));
        assertTrue(cites(db.evidence(), SpringConfigFacts.RULE_GETENV, J + "C.java", 3));

        EnvVarFact port = r.env("PORT");
        assertEquals("8080", port.defaultValue());
        assertEquals(2, port.evidence().size());

        EnvVarFact token = r.env("API_TOKEN");
        assertFalse(token.required());
        assertNull(token.defaultValue(), "default of a secret-looking name is never output");
    }
}

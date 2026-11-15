package com.proofix.backend.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecretRedactorTest {

    // Secret-shaped fixtures are assembled at runtime so that no literal
    // token-like string exists in the source tree (keeps secret scanners
    // and GitHub push protection quiet). None of these are real credentials.
    private static final String AWS_KEY_ID = "AK" + "IA" + "TESTFIXTURE00001";
    private static final String GITHUB_CLASSIC =
            "gh" + "p_" + "a".repeat(18) + "B".repeat(18);
    private static final String GITHUB_FINE_GRAINED =
            "github" + "_pat_" + "11ABCDEFG0" + "x".repeat(40);
    private static final String GOOGLE_KEY =
            "AI" + "za" + "Sy" + "C".repeat(20) + "-test_fixture";

    /** PEM armour lines, e.g. beginKey("RSA ") = BEGIN line for an RSA key. */
    private static String beginKey(String label) {
        return "-----BEGIN " + label + "PRIVATE" + " KEY-----";
    }

    private static String endKey(String label) {
        return "-----END " + label + "PRIVATE" + " KEY-----";
    }

    private final SecretRedactor redactor = new SecretRedactor();

    // ---------------------------------------------------------------
    // The 9 formats from the audit (all in configuration files)
    // ---------------------------------------------------------------

    static Stream<Arguments> auditFormats() {
        return Stream.of(
                Arguments.of(".env",
                        "DB_PASSWORD=hunter2",
                        "DB_PASSWORD=[REDACTED]"),
                Arguments.of(".env",
                        "GITHUB_TOKEN=ghp_abc123",
                        "GITHUB_TOKEN=[REDACTED]"),
                Arguments.of("src/main/resources/application.yml",
                        "client_secret: s3cr3t",
                        "client_secret: [REDACTED]"),
                Arguments.of("config/settings.json",
                        "{\"password\": \"hunter2\"}",
                        "{\"password\": \"[REDACTED]\"}"),
                Arguments.of("config/settings.json",
                        "{\"apiKey\": \"k-123\"}",
                        "{\"apiKey\": \"[REDACTED]\"}"),
                Arguments.of("src/main/resources/application.properties",
                        "spring.datasource.password=hunter2",
                        "spring.datasource.password=[REDACTED]"),
                Arguments.of("application.yaml",
                        "password: hunter2",
                        "password: [REDACTED]"),
                Arguments.of("application.properties",
                        "url=postgres://admin:hunter2@db:5432/x",
                        "url=postgres://admin:[REDACTED]@db:5432/x"),
                Arguments.of(".env.production",
                        "AWS_SECRET_ACCESS_KEY=abc",
                        "AWS_SECRET_ACCESS_KEY=[REDACTED]"));
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @MethodSource("auditFormats")
    void redactsAllNineAuditFormats(
            String path, String input, String expected) {
        SecretRedactor.RedactionResult result = redactor.redact(path, input);

        assertEquals(expected, result.content());
        assertTrue(result.redacted());
    }

    // ---------------------------------------------------------------
    // High-confidence patterns: applied to every file type
    // ---------------------------------------------------------------

    @Test
    void redactsPrivateKeyBlockAndKeepsSurroundingText() {
        String input = """
                class Keys {
                    String pem = \"""
                %s
                MIIEowIBAAKCAQEAfixturefixturefixture
                c29tZSBmaXh0dXJlIGJ5dGVz
                %s
                \""";
                }
                """.formatted(beginKey("RSA "), endKey("RSA "));

        SecretRedactor.RedactionResult result =
                redactor.redact("src/main/java/Keys.java", input);

        assertTrue(result.redacted());
        assertTrue(result.content().contains("[REDACTED_PRIVATE_KEY]"));
        assertFalse(result.content().contains("MIIEowIBAAKCAQEA"));
        assertFalse(result.content().contains(beginKey("RSA ")));
        assertTrue(result.content().startsWith("class Keys {"));
        assertTrue(result.content().strip().endsWith("}"));
    }

    @Test
    void redactsOpenSshAndUnlabelledPrivateKeyBlocks() {
        String input = "a\n" + beginKey("OPENSSH ") + "\nb3BlbnNzaA==\n"
                + endKey("OPENSSH ") + "\nb\n"
                + beginKey("") + "\nMIIEvQ==\n"
                + endKey("") + "\nc";

        SecretRedactor.RedactionResult result = redactor.redact("id_key.txt", input);

        assertEquals(
                "a\n[REDACTED_PRIVATE_KEY]\nb\n[REDACTED_PRIVATE_KEY]\nc",
                result.content());
    }

    @Test
    void redactsUnterminatedPrivateKeyToEndOfContent() {
        String input = "before\n" + beginKey("EC ") + "\nMHcCAQEEIfixture\nmore";

        SecretRedactor.RedactionResult result = redactor.redact("key.pem", input);

        assertEquals("before\n[REDACTED_PRIVATE_KEY]", result.content());
        assertTrue(result.redacted());
    }

    @Test
    void redactsAwsAccessKeyIdInSourceFile() {
        String input = "String id = \"" + AWS_KEY_ID + "\";";

        SecretRedactor.RedactionResult result =
                redactor.redact("src/main/java/Aws.java", input);

        assertEquals("String id = \"[REDACTED_AWS_ACCESS_KEY_ID]\";",
                result.content());
        assertTrue(result.redacted());
    }

    @Test
    void redactsGitHubTokensInSourceFile() {
        String input = "const a = '" + GITHUB_CLASSIC + "';\n"
                + "const b = '" + GITHUB_FINE_GRAINED + "';";

        SecretRedactor.RedactionResult result =
                redactor.redact("web/client.ts", input);

        assertEquals(
                "const a = '[REDACTED_GITHUB_TOKEN]';\n"
                        + "const b = '[REDACTED_GITHUB_TOKEN]';",
                result.content());
    }

    @Test
    void redactsGoogleApiKeyInSourceAndConfigurationFiles() {
        SecretRedactor.RedactionResult source = redactor.redact(
                "src/main/java/Gemini.java",
                "client.key(\"" + GOOGLE_KEY + "\");");
        SecretRedactor.RedactionResult config = redactor.redact(
                "application.yml",
                "gemini:\n  endpoint: " + GOOGLE_KEY + "\n");

        assertEquals("client.key(\"[REDACTED_GOOGLE_API_KEY]\");",
                source.content());
        assertEquals("gemini:\n  endpoint: [REDACTED_GOOGLE_API_KEY]\n",
                config.content());
        assertTrue(source.redacted());
        assertTrue(config.redacted());
    }

    @Test
    void redactsConnectionStringPasswordInSourceFile() {
        String input = "connect(\"mongodb+srv://app:pa55-w0rd@cluster0.example.net/db\");";

        SecretRedactor.RedactionResult result =
                redactor.redact("src/db.py", input);

        assertEquals(
                "connect(\"mongodb+srv://app:[REDACTED]@cluster0.example.net/db\");",
                result.content());
        assertTrue(result.redacted());
    }

    // ---------------------------------------------------------------
    // Config-file key/value rule details
    // ---------------------------------------------------------------

    @Test
    void redactsQuotedValuesContainingSpaces() {
        String input = """
                password: "correct horse battery staple"
                api_key: 'two words here'
                token: "escaped \\" quote inside"
                """;

        SecretRedactor.RedactionResult result =
                redactor.redact("application.yml", input);

        assertEquals("""
                password: "[REDACTED]"
                api_key: '[REDACTED]'
                token: "[REDACTED]"
                """, result.content());
        assertTrue(result.redacted());
    }

    @Test
    void redactsUnquotedValueToEndOfLine() {
        SecretRedactor.RedactionResult result = redactor.redact(
                "app.properties",
                "db.password=my secret pass\nserver.port=8082");

        assertEquals("db.password=[REDACTED]\nserver.port=8082",
                result.content());
    }

    @Test
    void redactsUnterminatedQuotedValueToEndOfLine() {
        SecretRedactor.RedactionResult result = redactor.redact(
                ".env", "SECRET_KEY=\"abc def\nNEXT=1");

        assertEquals("SECRET_KEY=\"[REDACTED]\nNEXT=1", result.content());
    }

    @Test
    void redactsSecretInsideQueryStringOfNonSensitiveKey() {
        SecretRedactor.RedactionResult result = redactor.redact(
                "application.properties",
                "spring.datasource.url=jdbc:postgresql://db/app?user=app&password=hunter2");

        assertEquals(
                "spring.datasource.url=jdbc:postgresql://db/app?user=app&password=[REDACTED]",
                result.content());
    }

    @Test
    void redactsPlaceholderThatCarriesLiteralDefault() {
        SecretRedactor.RedactionResult result = redactor.redact(
                "application.properties",
                "app.jwt.secret=${JWT_SECRET:hunter2}");

        assertEquals("app.jwt.secret=[REDACTED]", result.content());
        assertTrue(result.redacted());
    }

    @Test
    void keyNameRuleIsCaseInsensitiveAndRedactsEveryMatchingLine() {
        String input = """
                export Db_Password=one
                PRIVATE_KEY=two
                MY_CREDENTIALS=three
                APP_NAME=devbuddy
                """;

        SecretRedactor.RedactionResult result = redactor.redact(".env", input);

        assertEquals("""
                export Db_Password=[REDACTED]
                PRIVATE_KEY=[REDACTED]
                MY_CREDENTIALS=[REDACTED]
                APP_NAME=devbuddy
                """, result.content());
    }

    // ---------------------------------------------------------------
    // Things that must NOT be redacted
    // ---------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "spring.datasource.password=${DB_PASSWORD}",
            "github.api.token=${GITHUB_TOKEN:}",
            "app.jwt.secret = ${JWT_SECRET} # injected at runtime",
            "url=postgres://admin:${DB_PASSWORD}@db:5432/x",
            "password=",
            "secrets:",
    })
    void leavesPurePlaceholdersAndEmptyValuesInPropertiesUnredacted(String input) {
        SecretRedactor.RedactionResult result =
                redactor.redact("application.properties", input);

        assertEquals(input, result.content());
        assertFalse(result.redacted());
    }

    @Test
    void leavesPurePlaceholdersInYamlJsonAndWorkflowsUnredacted() {
        String yaml = "token: ${{ secrets.GITHUB_TOKEN }}\npassword: \"${DB_PASSWORD}\"\n";
        String json = "{\"apiKey\": \"${API_KEY}\"}";

        SecretRedactor.RedactionResult workflow =
                redactor.redact(".github/workflows/ci.yml", yaml);
        SecretRedactor.RedactionResult config =
                redactor.redact("config.json", json);

        assertEquals(yaml, workflow.content());
        assertEquals(json, config.content());
        assertFalse(workflow.redacted());
        assertFalse(config.redacted());
    }

    @Test
    void leavesPlainTextUnredacted() {
        String input = """
                # Never commit a password or token to git.
                description=Rotate the secret every month
                server.port=8082
                spring.application.name=devbuddy
                """;

        SecretRedactor.RedactionResult result =
                redactor.redact("application.properties", input);

        assertEquals(input, result.content());
        assertFalse(result.redacted());
    }

    @Test
    void doesNotApplyKeyNameRuleToJavaSource() {
        String input = """
                String password = request.getPassword();
                String token = jwtService.generateToken(userId, email);
                private static final String API_KEY_HEADER = "X-Api-Key";
                """;

        SecretRedactor.RedactionResult result =
                redactor.redact("src/main/java/com/acme/AuthService.java", input);

        assertEquals(input, result.content());
        assertFalse(result.redacted());
    }

    @Test
    void sameAssignmentIsRedactedInPropertiesButNotInJava() {
        String line = "DB_PASSWORD=hunter2";

        SecretRedactor.RedactionResult properties =
                redactor.redact("src/main/resources/application.properties", line);
        SecretRedactor.RedactionResult java =
                redactor.redact("src/main/java/Config.java", line);

        assertEquals("DB_PASSWORD=[REDACTED]", properties.content());
        assertTrue(properties.redacted());
        assertEquals(line, java.content());
        assertFalse(java.redacted());
    }

    @Test
    void returnsUnchangedEmptyContent() {
        SecretRedactor.RedactionResult result = redactor.redact(".env", "");

        assertEquals("", result.content());
        assertFalse(result.redacted());
    }

    @Test
    void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class,
                () -> redactor.redact(".env", null));
    }

    // ---------------------------------------------------------------
    // Configuration-file detection
    // ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "application.properties", "config/app.YML", "deploy/values.yaml",
            "package.json", ".env", "backend/.env.local", "prod.env"})
    void detectsConfigurationFiles(String path) {
        assertTrue(SecretRedactor.isConfigurationFile(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/App.java", "pom.xml", "Dockerfile", "README.md",
            "environment.ts", ""})
    void treatsOtherFilesAsSource(String path) {
        assertFalse(SecretRedactor.isConfigurationFile(path));
    }

    @Test
    void treatsNullPathAsSource() {
        assertFalse(SecretRedactor.isConfigurationFile(null));
        assertEquals("DB_PASSWORD=x",
                redactor.redact(null, "DB_PASSWORD=x").content());
    }

    // ---------------------------------------------------------------
    // Catastrophic backtracking guard
    // ---------------------------------------------------------------

    @Test
    void finishesAdversarialInputsWithinTimeLimit() {
        int n = 200_000;
        String[] adversarial = {
                "a".repeat(n),
                "password".repeat(n / 8),
                "a.".repeat(n / 2) + "password",
                "\"password\": \"" + "\\".repeat(n),
                "password=" + "'".repeat(n),
                "postgres://" + "a:".repeat(n / 2),
                "x://" + "u".repeat(n) + ":" + "p".repeat(n),
                "-----BEGIN ".repeat(n / 11),
                beginKey("").repeat(n / 27),
                ("AK" + "IA").repeat(n / 4),
                ("gh" + "p_").repeat(n / 4),
                "AIza".repeat(n / 4) + "-".repeat(n),
                "k".repeat(127) + "_token" + " ".repeat(n) + "=",
                ("pwd=" + " ".repeat(30) + "\n").repeat(n / 40),
                "password='' ".repeat(n / 12),
                "token=\"\\\" ".repeat(n / 10),
                "a" + " ".repeat(n) + "b #",
        };

        // Linear-time redaction of all inputs takes ~1 s on a warm JVM and up
        // to ~3 s on a cold one. Catastrophic or quadratic backtracking on
        // 200k-character inputs would take minutes, so 10 s still proves the
        // property without flaking on slow machines.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (String input : adversarial) {
                redactor.redact("application.properties", input);
                redactor.redact("src/Main.java", input);
            }
        });
    }
}

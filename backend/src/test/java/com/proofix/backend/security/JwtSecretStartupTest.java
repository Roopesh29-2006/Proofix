package com.proofix.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Proves the application context refuses to start without a usable JWT
 * signing secret, and that the main configuration has no fallback key.
 */
class JwtSecretStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtService.class)
            .withPropertyValues("app.jwt.expiration-ms=3600000");

    @Test
    void contextFailsToStartWhenSecretIsEmpty() {
        // Same value the main configuration resolves to when JWT_SECRET is unset.
        runner.withPropertyValues("app.jwt.secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("JWT_SECRET");
                });
    }

    @Test
    void contextFailsToStartWhenSecretPropertyIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("JWT_SECRET");
        });
    }

    @Test
    void contextFailsToStartWithWeakSecret() {
        runner.withPropertyValues("app.jwt.secret=" + randomBase64Key(16))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("at least 32 bytes");
                });
    }

    @Test
    void contextFailsToStartWithInvalidBase64Secret() {
        runner.withPropertyValues("app.jwt.secret=%%%not-base64%%%")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("base64");
                });
    }

    @Test
    void contextStartsWithValidSecret() {
        runner.withPropertyValues("app.jwt.secret=" + randomBase64Key(32))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtService.class);
                });
    }

    @Test
    void mainConfigurationHasNoFallbackSecret() throws Exception {
        // src/main/resources/application.properties (the test profile file is
        // application-test.properties and is not loaded here).
        Properties main = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        assertThat(main.getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET:}");
    }

    private static String randomBase64Key(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}

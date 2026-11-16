package com.proofix.backend;

import java.util.List;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Refuses to start any Spring test context unless the datasource points at
 * the {@code proofix_test} database. Several tests commit and delete rows,
 * and Flyway migrates on startup, so this runs before the context refreshes
 * (registered in META-INF/spring.factories), i.e. before any connection is
 * opened.
 */
public class TestDatabaseGuard implements ContextCustomizerFactory {

    public static final String REQUIRED_DATABASE = "proofix_test";

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new Guard();
    }

    /** Database name from a PostgreSQL JDBC URL, or null if it cannot be found. */
    static String databaseName(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            return null;
        }
        String rest = jdbcUrl.substring("jdbc:postgresql://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String path = rest.substring(slash + 1);
        int query = path.indexOf('?');
        String name = query < 0 ? path : path.substring(0, query);
        return name.isEmpty() ? null : name;
    }

    static void check(String jdbcUrl) {
        String name = databaseName(jdbcUrl);
        if (!REQUIRED_DATABASE.equals(name)) {
            throw new IllegalStateException(
                    "Refusing to run tests: spring.datasource.url must point at the '"
                            + REQUIRED_DATABASE + "' database, but it points at '" + name
                            + "'. Tests commit and delete rows.");
        }
    }

    private static final class Guard implements ContextCustomizer {

        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            check(context.getEnvironment().getProperty("spring.datasource.url"));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Guard;
        }

        @Override
        public int hashCode() {
            return Guard.class.hashCode();
        }
    }
}

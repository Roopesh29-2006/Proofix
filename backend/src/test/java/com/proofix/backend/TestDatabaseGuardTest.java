package com.proofix.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

class TestDatabaseGuardTest {

    @Test
    void acceptsTheTestDatabase() {
        assertDoesNotThrow(() -> TestDatabaseGuard.check(
                "jdbc:postgresql://localhost:5432/proofix_test"));
        assertDoesNotThrow(() -> TestDatabaseGuard.check(
                "jdbc:postgresql://localhost:5432/proofix_test?sslmode=disable"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/Proofix_db",
            "jdbc:postgresql://localhost:5432/proofix_test2",
            "jdbc:postgresql://localhost:5432/Proofix_test",
            "jdbc:postgresql://localhost:5432/",
            "jdbc:postgresql://localhost:5432",
            "jdbc:h2:mem:proofix_test",
    })
    void rejectsAnythingElse(String url) {
        assertThrows(IllegalStateException.class, () -> TestDatabaseGuard.check(url));
    }

    @Test
    void extractsTheDatabaseName() {
        assertEquals("proofix_test",
                TestDatabaseGuard.databaseName("jdbc:postgresql://h:1/proofix_test?a=b"));
        assertNull(TestDatabaseGuard.databaseName("jdbc:postgresql://h:1"));
    }

    /** The guard is registered and the live connection really is proofix_test. */
    @SpringBootTest
    @ActiveProfiles("test")
    @org.junit.jupiter.api.Nested
    class LiveConnection {

        @Autowired
        private JdbcTemplate jdbc;

        @Test
        void connectedDatabaseIsProofixTest() {
            assertEquals("proofix_test",
                    jdbc.queryForObject("SELECT current_database()", String.class));
        }
    }
}

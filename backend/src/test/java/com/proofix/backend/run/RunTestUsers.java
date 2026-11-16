package com.proofix.backend.run;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.proofix.backend.security.JwtService;

/**
 * Creates throwaway users in proofix_test and deletes them, with their Runs
 * and events, afterwards. Used by tests that need committed data (HTTP
 * requests and concurrency), so they cannot rely on transaction rollback.
 */
final class RunTestUsers {

    record TestUser(UUID id, String token) {
    }

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final List<UUID> created = new ArrayList<>();

    RunTestUsers(JdbcTemplate jdbc, JwtService jwtService) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
    }

    TestUser create() {
        String email = "run-" + UUID.randomUUID() + "@example.test";
        UUID id = jdbc.queryForObject(
                "INSERT INTO users (first_name, last_name, email) "
                        + "VALUES ('Run', 'Test', ?) RETURNING user_id",
                UUID.class, email);
        created.add(id);
        return new TestUser(id, jwtService.generateToken(id, email));
    }

    long runCount(UUID owner) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM runs WHERE owner_user_id = ?", Long.class, owner);
        return count == null ? 0 : count;
    }

    void deleteAll() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM run_events WHERE run_id IN "
                    + "(SELECT id FROM runs WHERE owner_user_id = ?)", id);
            jdbc.update("DELETE FROM runs WHERE owner_user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE user_id = ?", id);
        }
        created.clear();
    }
}

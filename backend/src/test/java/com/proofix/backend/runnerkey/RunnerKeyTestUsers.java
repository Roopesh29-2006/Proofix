package com.proofix.backend.runnerkey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.proofix.backend.security.JwtService;

/**
 * Throwaway users in proofix_test for runner-key tests, deleted afterwards
 * together with their Runs, events and projects (runner_keys go with the user
 * through ON DELETE CASCADE). TestDatabaseGuard guarantees proofix_test.
 */
public final class RunnerKeyTestUsers {

    public record TestUser(UUID id, String token) {
    }

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final List<UUID> created = new ArrayList<>();

    public RunnerKeyTestUsers(JdbcTemplate jdbc, JwtService jwtService) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
    }

    public TestUser create() {
        String email = "rk-" + UUID.randomUUID() + "@example.test";
        UUID id = jdbc.queryForObject(
                "INSERT INTO users (first_name, last_name, email) "
                        + "VALUES ('Runner', 'Test', ?) RETURNING user_id",
                UUID.class, email);
        created.add(id);
        return new TestUser(id, jwtService.generateToken(id, email));
    }

    public long keyCount(UUID owner) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM runner_keys WHERE owner_user_id = ?", Long.class, owner);
        return count == null ? 0 : count;
    }

    public void deleteAll() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM run_events WHERE run_id IN "
                    + "(SELECT id FROM runs WHERE owner_user_id = ?)", id);
            jdbc.update("DELETE FROM runs WHERE owner_user_id = ?", id);
            jdbc.update("DELETE FROM projects WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE user_id = ?", id);
        }
        created.clear();
    }
}

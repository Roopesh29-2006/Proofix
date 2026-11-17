package com.proofix.backend.run.runner;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.proofix.backend.run.Run;
import com.proofix.backend.run.RunActor;
import com.proofix.backend.run.RunBudgets;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunStatus;
import com.proofix.backend.runnerkey.CreatedRunnerKey;
import com.proofix.backend.runnerkey.RunnerKeyService;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers;
import com.proofix.backend.runnerkey.RunnerKeyTestUsers.TestUser;
import com.proofix.backend.security.JwtService;

/**
 * Users, runner keys and Runs for runner API tests (committed data in
 * proofix_test, removed by {@link #deleteAll()}). Runs reach their state
 * through legal transitions only.
 */
final class RunnerFixtures {

    static final String REPO = "https://github.com/octo/demo";
    static final String VERSION = "1.2.0";
    static final String VERSION_HEADER = "X-Runner-Version";

    private final RunnerKeyTestUsers users;
    private final RunnerKeyService keys;
    private final RunStateMachine machine;
    private final JdbcTemplate jdbc;

    RunnerFixtures(JdbcTemplate jdbc, JwtService jwtService, RunnerKeyService keys,
            RunStateMachine machine) {
        this.users = new RunnerKeyTestUsers(jdbc, jwtService);
        this.keys = keys;
        this.machine = machine;
        this.jdbc = jdbc;
    }

    TestUser user() {
        return users.create();
    }

    CreatedRunnerKey key(TestUser owner) {
        return keys.create(owner.id(), "runner");
    }

    Run queued(TestUser owner) {
        return machine.create(owner.id(), REPO, new RunBudgets(30, 50, 100));
    }

    /** A Run moved along {@code path} by the system (no runner set). */
    Run run(TestUser owner, List<RunStatus> path) {
        Run run = queued(owner);
        for (RunStatus step : path) {
            run = machine.transition(run.getId(), step, RunActor.SYSTEM, "fixture");
        }
        return run;
    }

    Run approvedTesting(TestUser owner) {
        return run(owner, List.of(RunStatus.ANALYZING, RunStatus.AWAITING_PLAN_OK, RunStatus.TESTING));
    }

    String status(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM runs WHERE id = ?", String.class, runId);
    }

    String runnerId(UUID runId) {
        return jdbc.queryForObject("SELECT runner_id FROM runs WHERE id = ?", String.class, runId);
    }

    void deleteAll() {
        users.deleteAll();
    }
}

package com.proofix.backend.run.runner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.proofix.backend.run.Run;
import com.proofix.backend.run.RunActor;
import com.proofix.backend.run.RunBudgets;
import com.proofix.backend.run.RunStateMachine;
import com.proofix.backend.run.RunStatus;
import com.proofix.backend.run.RunWorkerOps;
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
    static final String INSTANCE_HEADER = "X-Runner-Instance";
    static final String INSTANCE = "box-1";
    static final String NOT_FOUND_BODY = "{\"error\":\"Run not found\"}";

    /** A valid report body (schema v1). */
    static final String VALID_REPORT = "{\"schemaVersion\": 1, \"journeys\": null, \"recipe\": {"
            + "\"stack\": \"java-spring-boot\", "
            + "\"buildCommand\": \"./mvnw -q package -DskipTests\", "
            + "\"startCommand\": \"java -jar target/app.jar\", "
            + "\"port\": 8080, "
            + "\"envVarNames\": [\"DB_URL\", \"DB_PASSWORD\"], "
            + "\"needsDatabase\": \"postgres\", "
            + "\"evidence\": [\"pom.xml\", \"src/main/resources/application.properties\"], "
            + "\"javaVersion\": 21, "
            + "\"healthCheckPath\": \"/actuator/health\"}}";

    static final String VALID_FAIL = "{\"code\": \"BUILD_FAILED\", \"reason\": \"mvn exited 1\"}";

    private final RunnerKeyTestUsers users;
    private final RunnerKeyService keys;
    private final RunStateMachine machine;
    private final RunWorkerOps ops;
    private final JdbcTemplate jdbc;

    RunnerFixtures(JdbcTemplate jdbc, JwtService jwtService, RunnerKeyService keys,
            RunStateMachine machine, RunWorkerOps ops) {
        this.users = new RunnerKeyTestUsers(jdbc, jwtService);
        this.keys = keys;
        this.machine = machine;
        this.ops = ops;
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

    /** A QUEUED Run of {@code owner}, claimed by {@code key}/{@code instance} (now ANALYZING). */
    UUID claimed(TestUser owner, CreatedRunnerKey key, String instance) {
        UUID id = queued(owner).getId();
        UUID got = ops.claimNextQueuedForOwner(owner.id(), runnerId(key, instance))
                .orElseThrow().runId();
        if (!got.equals(id)) {
            throw new IllegalStateException("fixture claimed an unexpected Run");
        }
        return id;
    }

    static String runnerId(CreatedRunnerKey key, String instance) {
        return key.key().getId() + ":" + instance;
    }

    /** POST to a runner endpoint with key, version and instance headers. */
    static MockHttpServletRequestBuilder runnerPost(String path, CreatedRunnerKey key,
            String instance) {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.plaintext())
                .header(VERSION_HEADER, VERSION)
                .header(INSTANCE_HEADER, instance);
    }

    static MockHttpServletRequestBuilder runnerPost(String path, CreatedRunnerKey key,
            String instance, String json) {
        return runnerPost(path, key, instance)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    String status(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM runs WHERE id = ?", String.class, runId);
    }

    String runnerIdOf(UUID runId) {
        return jdbc.queryForObject("SELECT runner_id FROM runs WHERE id = ?", String.class, runId);
    }

    String lastRunnerIdOf(UUID runId) {
        return jdbc.queryForObject("SELECT last_runner_id FROM runs WHERE id = ?", String.class,
                runId);
    }

    String plan(UUID runId) {
        return jdbc.queryForObject("SELECT plan::text FROM runs WHERE id = ?", String.class, runId);
    }

    String failureCode(UUID runId) {
        return jdbc.queryForObject("SELECT failure_code FROM runs WHERE id = ?", String.class,
                runId);
    }

    String failureReason(UUID runId) {
        return jdbc.queryForObject("SELECT failure_reason FROM runs WHERE id = ?", String.class,
                runId);
    }

    void requestCancel(UUID runId) {
        jdbc.update("UPDATE runs SET cancel_requested = true WHERE id = ?", runId);
    }

    void deleteAll() {
        users.deleteAll();
    }
}

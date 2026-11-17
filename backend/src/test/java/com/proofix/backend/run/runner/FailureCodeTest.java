package com.proofix.backend.run.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.proofix.backend.run.FailureCode;

/**
 * FailureCode is pinned three ways: the DB CHECK lists exactly the enum
 * (no drift), runner vs server-only codes are exactly as decided, and the
 * hint wording is the approved text.
 */
@SpringBootTest
@ActiveProfiles("test")
class FailureCodeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theDatabaseCheckListsExactlyTheEnum() {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'runs_failure_code_check'", String.class);
        Set<String> inCheck = new TreeSet<>();
        Matcher m = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        while (m.find()) {
            inCheck.add(m.group(1));
        }
        inCheck.remove("FAILED"); // the status = 'FAILED' guard, not a code

        Set<String> inEnum = Arrays.stream(FailureCode.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(inEnum, inCheck, "FailureCode and runs_failure_code_check drifted: "
                + definition);
        assertTrue(definition.contains("'FAILED'"), "the code must require status FAILED");
    }

    @Test
    void onlyTheServerMaySetRunnerLostAndBudgetExceeded() {
        Set<FailureCode> serverOnly = EnumSet.noneOf(FailureCode.class);
        for (FailureCode code : FailureCode.values()) {
            if (!code.isRunnerReportable()) {
                serverOnly.add(code);
            }
        }
        assertEquals(EnumSet.of(FailureCode.RUNNER_LOST, FailureCode.BUDGET_EXCEEDED), serverOnly);
        assertTrue(FailureCode.runnerReportable("RUNNER_LOST").isEmpty());
        assertTrue(FailureCode.runnerReportable("BUDGET_EXCEEDED").isEmpty());
        assertTrue(FailureCode.runnerReportable("build_failed").isEmpty());
        assertTrue(FailureCode.runnerReportable(null).isEmpty());
        assertEquals(FailureCode.BUILD_FAILED, FailureCode.runnerReportable("BUILD_FAILED").get());
    }

    @Test
    void hintsAreTheApprovedWording() {
        Map<FailureCode, String> approved = new LinkedHashMap<>();
        approved.put(FailureCode.DOCKER_NOT_RUNNING, "Docker isn't running on your machine. "
                + "Start Docker Desktop (or the Docker service), wait until it reports it is "
                + "running, then start a new Run.");
        approved.put(FailureCode.INSUFFICIENT_RESOURCES, "Your machine didn't have enough free "
                + "memory, CPU or disk for the app. Close other heavy programs, free disk space "
                + "or raise Docker's resource limits, then start a new Run.");
        approved.put(FailureCode.BUILD_FAILED, "The project didn't build. Run the build command "
                + "shown in the plan on your machine, fix the first error it reports, then start "
                + "a new Run.");
        approved.put(FailureCode.NO_MAIN_CLASS, "No runnable entry point was found. Make sure the "
                + "build produces a runnable jar with a main class (for Spring Boot, a class "
                + "annotated @SpringBootApplication with a main method).");
        approved.put(FailureCode.APP_DID_NOT_START, "The app built but exited during startup. Run "
                + "the start command locally and read the first error; missing configuration or "
                + "an unreachable database is the usual cause.");
        approved.put(FailureCode.PORT_NOT_OPEN, "The app started but nothing answered on the "
                + "expected port. Check the port the app listens on (for Spring Boot, server.port) "
                + "and that it binds to 0.0.0.0, not only localhost.");
        approved.put(FailureCode.MISSING_ENV_VAR, "The app needs an environment variable that "
                + "wasn't set. Set the variables listed in the plan on your machine (DevBuddy only "
                + "ever sees their names), then start a new Run.");
        approved.put(FailureCode.GEMINI_KEY_INVALID, "The runner's Gemini API key was rejected. "
                + "Create or copy a valid key in Google AI Studio, update it in the runner's "
                + "configuration, then start a new Run.");
        approved.put(FailureCode.GEMINI_QUOTA_EXCEEDED, "The runner's Gemini API key ran out of "
                + "quota or hit a rate limit. Wait for the quota to reset or use a key with more "
                + "quota, then start a new Run.");
        approved.put(FailureCode.UNKNOWN, "The runner hit an error it couldn't classify. Check the "
                + "runner's log on your machine for details, then start a new Run.");
        approved.put(FailureCode.RUNNER_LOST, "Your runner stopped responding. Check it's still "
                + "running and your machine is awake, then start a new Run.");
        approved.put(FailureCode.BUDGET_EXCEEDED, "The Run took longer than its time budget. Try a "
                + "smaller test plan, then start a new Run.");

        assertEquals(FailureCode.values().length, approved.size());
        approved.forEach((code, hint) -> assertEquals(hint, code.fixHint(), code.name()));
    }
}

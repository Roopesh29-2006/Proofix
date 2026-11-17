package com.proofix.backend.run;

import java.util.Optional;

/**
 * Why a Run failed, with a fix hint that lives on the server. A runner can
 * report only the runner-reportable codes and never supplies hint text;
 * RUNNER_LOST and BUDGET_EXCEEDED are set by the reaper only. The list must
 * match runs_failure_code_check (V4).
 */
public enum FailureCode {

    DOCKER_NOT_RUNNING(true, "Docker isn't running on your machine. Start Docker Desktop (or the "
            + "Docker service), wait until it reports it is running, then start a new Run."),
    INSUFFICIENT_RESOURCES(true, "Your machine didn't have enough free memory, CPU or disk for the "
            + "app. Close other heavy programs, free disk space or raise Docker's resource limits, "
            + "then start a new Run."),
    BUILD_FAILED(true, "The project didn't build. Run the build command shown in the plan on your "
            + "machine, fix the first error it reports, then start a new Run."),
    NO_MAIN_CLASS(true, "No runnable entry point was found. Make sure the build produces a "
            + "runnable jar with a main class (for Spring Boot, a class annotated "
            + "@SpringBootApplication with a main method)."),
    APP_DID_NOT_START(true, "The app built but exited during startup. Run the start command "
            + "locally and read the first error; missing configuration or an unreachable database "
            + "is the usual cause."),
    PORT_NOT_OPEN(true, "The app started but nothing answered on the expected port. Check the "
            + "port the app listens on (for Spring Boot, server.port) and that it binds to "
            + "0.0.0.0, not only localhost."),
    MISSING_ENV_VAR(true, "The app needs an environment variable that wasn't set. Set the "
            + "variables listed in the plan on your machine (DevBuddy only ever sees their names), "
            + "then start a new Run."),
    GEMINI_KEY_INVALID(true, "The runner's Gemini API key was rejected. Create or copy a valid key "
            + "in Google AI Studio, update it in the runner's configuration, then start a new Run."),
    GEMINI_QUOTA_EXCEEDED(true, "The runner's Gemini API key ran out of quota or hit a rate limit. "
            + "Wait for the quota to reset or use a key with more quota, then start a new Run."),
    UNKNOWN(true, "The runner hit an error it couldn't classify. Check the runner's log on your "
            + "machine for details, then start a new Run."),

    /** Server only: the reaper found a stale heartbeat. */
    RUNNER_LOST(false, "Your runner stopped responding. Check it's still running and your machine "
            + "is awake, then start a new Run."),
    /** Server only: worker time exceeded max_minutes. */
    BUDGET_EXCEEDED(false, "The Run took longer than its time budget. Try a smaller test plan, "
            + "then start a new Run.");

    private final boolean runnerReportable;
    private final String fixHint;

    FailureCode(boolean runnerReportable, String fixHint) {
        this.runnerReportable = runnerReportable;
        this.fixHint = fixHint;
    }

    public boolean isRunnerReportable() {
        return runnerReportable;
    }

    public String fixHint() {
        return fixHint;
    }

    /** The code a runner may report under this exact (case-sensitive) name, if any. */
    public static Optional<FailureCode> runnerReportable(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (FailureCode code : values()) {
            if (code.runnerReportable && code.name().equals(name)) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }
}

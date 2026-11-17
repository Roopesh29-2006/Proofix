package com.proofix.backend.run.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.proofix.backend.run.worker.RunnerProperties;

/**
 * Startup WARNING when the in-process FakeRunExecutor and the runner API are
 * both enabled: the fake claims QUEUED Runs of ANY user (global claim), so
 * real runners would lose Runs to it. The fake is for local development only.
 */
@Component
public class RunnerApiSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(RunnerApiSafetyCheck.class);

    private final RunnerApiProperties api;
    private final RunnerProperties runner;

    public RunnerApiSafetyCheck(RunnerApiProperties api, RunnerProperties runner) {
        this.api = api;
        this.runner = runner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (api.enabled() && runner.fake().enabled()) {
            log.warn("FakeRunExecutor is enabled together with the runner API "
                    + "(devbuddy.runner.fake.enabled=true, devbuddy.runner.api.enabled=true). "
                    + "The fake worker claims QUEUED Runs of ANY user, so real runners will lose "
                    + "Runs to it. Use the fake for local development only.");
        }
    }
}

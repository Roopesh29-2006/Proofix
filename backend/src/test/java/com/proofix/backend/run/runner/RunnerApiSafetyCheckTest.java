package com.proofix.backend.run.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.proofix.backend.run.worker.RunnerProperties;

/** The startup WARNING when the fake executor and the runner API are both on. */
@ExtendWith(OutputCaptureExtension.class)
class RunnerApiSafetyCheckTest {

    private static RunnerProperties runner(boolean fake) {
        return new RunnerProperties(null, 2000, 10000, 30000,
                new RunnerProperties.Fake(fake, 500, 3));
    }

    private static RunnerApiProperties api(boolean enabled) {
        return new RunnerApiProperties(enabled, "0.1.0", 65536);
    }

    @Test
    void warnsWhenBothAreEnabled(CapturedOutput output) {
        new RunnerApiSafetyCheck(api(true), runner(true)).check();

        assertTrue(output.getAll().contains("WARN"), output.getAll());
        assertTrue(output.getAll().contains("FakeRunExecutor"), output.getAll());
        assertTrue(output.getAll().contains("ANY user"), output.getAll());
    }

    @Test
    void silentWhenTheFakeIsOff(CapturedOutput output) {
        new RunnerApiSafetyCheck(api(true), runner(false)).check();

        assertFalse(output.getAll().contains("FakeRunExecutor"), output.getAll());
    }

    @Test
    void silentWhenTheRunnerApiIsOff(CapturedOutput output) {
        new RunnerApiSafetyCheck(api(false), runner(true)).check();

        assertFalse(output.getAll().contains("FakeRunExecutor"), output.getAll());
    }
}

package com.proofix.backend.run.worker;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** FakeRunExecutor exists only when devbuddy.runner.fake.enabled=true. */
class FakeRunExecutorWiringTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class ByDefault {

        @Autowired
        private ApplicationContext context;

        @Test
        void noExecutorBean() {
            // application.properties sets devbuddy.runner.fake.enabled=false.
            assertNull(context.getBeanProvider(RunExecutor.class).getIfAvailable());
        }
    }

    @Nested
    @SpringBootTest(properties = "devbuddy.runner.fake.enabled=true")
    @ActiveProfiles("test")
    class WhenEnabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void fakeExecutorIsTheExecutor() {
            assertInstanceOf(FakeRunExecutor.class, context.getBean(RunExecutor.class));
        }
    }

    @Test
    void heartbeatTimeoutMustExceedAFakeStep() {
        assertThrows(IllegalArgumentException.class, () -> new RunnerProperties(
                null, 2_000, 10_000, 500, new RunnerProperties.Fake(true, 500, 3)));
    }
}

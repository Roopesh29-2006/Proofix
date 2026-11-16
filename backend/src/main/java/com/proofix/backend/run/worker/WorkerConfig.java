package com.proofix.backend.run.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(RunnerProperties.class)
public class WorkerConfig {

    /**
     * Turns on the scheduled poller and reaper. On by default; the test
     * profile turns it off and tests call pollOnce/reapOnce directly.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "devbuddy.runner.scheduling", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}

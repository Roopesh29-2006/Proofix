package com.proofix.backend.run.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.proofix.backend.security.RunnerVersion;

/**
 * {@code devbuddy.runner.api.*}. Invalid values fail at startup.
 *
 * @param enabled      the runner API (/api/runner/**) is served
 * @param minVersion   oldest X-Runner-Version accepted; older runners get 426
 * @param maxBodyBytes request body cap on the runner API (413 above it)
 */
@ConfigurationProperties(prefix = "devbuddy.runner.api")
public record RunnerApiProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0.1.0") String minVersion,
        @DefaultValue("65536") int maxBodyBytes) {

    public RunnerApiProperties {
        RunnerVersion.require(minVersion, "devbuddy.runner.api.min-version");
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("devbuddy.runner.api.max-body-bytes must be positive");
        }
    }

    public RunnerVersion minimumVersion() {
        return RunnerVersion.require(minVersion, "devbuddy.runner.api.min-version");
    }
}

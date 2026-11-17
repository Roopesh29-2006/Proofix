package com.proofix.backend.runnerkey;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code devbuddy.runner-keys.*}. Invalid values fail at startup.
 *
 * @param maxActivePerUser maximum active (not revoked, not expired) keys per user
 */
@ConfigurationProperties(prefix = "devbuddy.runner-keys")
public record RunnerKeyProperties(@DefaultValue("5") int maxActivePerUser) {

    public RunnerKeyProperties {
        if (maxActivePerUser < 1) {
            throw new IllegalArgumentException("devbuddy.runner-keys.max-active-per-user must be positive");
        }
    }
}

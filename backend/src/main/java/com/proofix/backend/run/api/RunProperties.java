package com.proofix.backend.run.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.proofix.backend.run.RunBudgets;

/** {@code devbuddy.runs.*}. Invalid values fail at startup. */
@ConfigurationProperties(prefix = "devbuddy.runs")
public record RunProperties(
        @DefaultValue("3") int maxActivePerUser,
        @DefaultValue Defaults defaults) {

    public RunProperties {
        if (maxActivePerUser < 1) {
            throw new IllegalArgumentException("devbuddy.runs.max-active-per-user must be >= 1");
        }
        // Validates the default budgets at startup.
        defaults.budgets();
    }

    public record Defaults(
            @DefaultValue("30") int maxMinutes,
            @DefaultValue("50") int maxGeminiCalls,
            @DefaultValue("100") int maxUsers) {

        public RunBudgets budgets() {
            return new RunBudgets(maxMinutes, maxGeminiCalls, maxUsers);
        }
    }
}

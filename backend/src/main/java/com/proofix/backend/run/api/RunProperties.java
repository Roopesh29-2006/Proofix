package com.proofix.backend.run.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.proofix.backend.run.RunBudgets;
import com.proofix.backend.run.RunLimits;

/** {@code devbuddy.runs.*}. Invalid values fail at startup. */
@ConfigurationProperties(prefix = "devbuddy.runs")
public record RunProperties(
        @DefaultValue("3") int maxActivePerUser,
        @DefaultValue("10") int maxUnfinishedPerUser,
        @DefaultValue Defaults defaults) {

    public RunProperties {
        // Validate at startup (parameters: fields are not assigned yet here).
        new RunLimits(maxActivePerUser, maxUnfinishedPerUser);
        defaults.budgets();
    }

    public RunLimits limits() {
        return new RunLimits(maxActivePerUser, maxUnfinishedPerUser);
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

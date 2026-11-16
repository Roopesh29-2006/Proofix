package com.proofix.backend.run;

/** Per-Run limits. All must be positive. */
public record RunBudgets(int maxMinutes, int maxGeminiCalls, int maxUsers) {

    public RunBudgets {
        if (maxMinutes <= 0 || maxGeminiCalls <= 0 || maxUsers <= 0) {
            throw new IllegalArgumentException("Run budgets must be positive");
        }
    }
}

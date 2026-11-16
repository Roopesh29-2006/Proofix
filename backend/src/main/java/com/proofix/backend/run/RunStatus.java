package com.proofix.backend.run;

/**
 * Lifecycle of a Run. Allowed moves live in {@link RunTransitions}; only
 * {@link RunStateMachine} changes a Run's status.
 */
public enum RunStatus {
    QUEUED,
    ANALYZING,
    AWAITING_PLAN_OK,
    TESTING,
    INVESTIGATING,
    AWAITING_FIX_OK,
    DONE,
    FAILED,
    CANCELLED;

    /** DONE, FAILED, CANCELLED: nothing follows. */
    public boolean isFinal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /** A worker is (or should be) doing work; this time counts toward max_minutes. */
    public boolean isActive() {
        return this == ANALYZING || this == TESTING || this == INVESTIGATING;
    }

    /** Waiting for a human approval. */
    public boolean isAwaiting() {
        return this == AWAITING_PLAN_OK || this == AWAITING_FIX_OK;
    }
}

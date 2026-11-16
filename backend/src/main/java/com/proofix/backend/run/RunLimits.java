package com.proofix.backend.run;

/**
 * Per-user limits.
 *
 * @param maxActive     QUEUED + active Runs (checked on create and on plan approval)
 * @param maxUnfinished QUEUED + active + AWAITING Runs (checked on create)
 */
public record RunLimits(int maxActive, int maxUnfinished) {

    public RunLimits {
        if (maxActive < 1 || maxUnfinished < 1) {
            throw new IllegalArgumentException("Run limits must be >= 1");
        }
    }
}

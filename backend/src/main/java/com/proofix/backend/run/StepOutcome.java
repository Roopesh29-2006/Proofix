package com.proofix.backend.run;

/** Result of a locked worker step. */
public enum StepOutcome {
    /** Nothing pending; keep working. */
    CONTINUE,
    /** The requested status change was applied. */
    MOVED,
    /** cancel_requested was set; the Run is now CANCELLED. Stop. */
    CANCELLED,
    /** The Run is no longer this runner's (reaped, other runner, moved on). Stop. */
    LOST
}

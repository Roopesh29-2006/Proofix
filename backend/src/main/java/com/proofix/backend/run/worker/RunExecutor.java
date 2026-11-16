package com.proofix.backend.run.worker;

import com.proofix.backend.run.ClaimedRun;

/**
 * Does the work for a claimed Run. Build 1 has only {@link FakeRunExecutor};
 * a real runner (Gemini, sandbox, k6) replaces it later.
 *
 * <p>Contract: advance the Run only through {@code RunWorkerOps}; heartbeat
 * while working; stop at the next check when the Run is cancelled or no
 * longer owned; return when the Run parks at an AWAITING state or finishes.
 */
public interface RunExecutor {

    void execute(ClaimedRun claim);
}

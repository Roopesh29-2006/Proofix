package com.proofix.backend.run;

import static com.proofix.backend.run.RunStatus.ANALYZING;
import static com.proofix.backend.run.RunStatus.AWAITING_FIX_OK;
import static com.proofix.backend.run.RunStatus.AWAITING_PLAN_OK;
import static com.proofix.backend.run.RunStatus.CANCELLED;
import static com.proofix.backend.run.RunStatus.DONE;
import static com.proofix.backend.run.RunStatus.FAILED;
import static com.proofix.backend.run.RunStatus.INVESTIGATING;
import static com.proofix.backend.run.RunStatus.QUEUED;
import static com.proofix.backend.run.RunStatus.TESTING;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The allowed status moves. Anything not listed here is illegal. */
public final class RunTransitions {

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = build();

    private RunTransitions() {
    }

    private static Map<RunStatus, Set<RunStatus>> build() {
        Map<RunStatus, Set<RunStatus>> allowed = new EnumMap<>(RunStatus.class);
        allowed.put(QUEUED, EnumSet.of(ANALYZING, CANCELLED, FAILED));
        allowed.put(ANALYZING, EnumSet.of(AWAITING_PLAN_OK, CANCELLED, FAILED));
        allowed.put(AWAITING_PLAN_OK, EnumSet.of(TESTING, CANCELLED));
        allowed.put(TESTING, EnumSet.of(INVESTIGATING, CANCELLED, FAILED));
        allowed.put(INVESTIGATING, EnumSet.of(AWAITING_FIX_OK, DONE, CANCELLED, FAILED));
        allowed.put(AWAITING_FIX_OK, EnumSet.of(DONE, CANCELLED));
        allowed.put(DONE, EnumSet.noneOf(RunStatus.class));
        allowed.put(FAILED, EnumSet.noneOf(RunStatus.class));
        allowed.put(CANCELLED, EnumSet.noneOf(RunStatus.class));

        Map<RunStatus, Set<RunStatus>> frozen = new EnumMap<>(RunStatus.class);
        allowed.forEach((from, to) -> frozen.put(from, Collections.unmodifiableSet(to)));
        return Collections.unmodifiableMap(frozen);
    }

    public static boolean isAllowed(RunStatus from, RunStatus to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }

    public static Set<RunStatus> allowedFrom(RunStatus from) {
        return ALLOWED.get(from);
    }
}

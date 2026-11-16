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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks RunTransitions against the specification table, written out here
 * independently of the production code.
 */
class RunTransitionsTest {

    /** The specification table. Anything not listed is illegal. */
    private static final Map<RunStatus, Set<RunStatus>> SPEC = spec();

    private static Map<RunStatus, Set<RunStatus>> spec() {
        Map<RunStatus, Set<RunStatus>> spec = new EnumMap<>(RunStatus.class);
        spec.put(QUEUED, EnumSet.of(ANALYZING, CANCELLED, FAILED));
        spec.put(ANALYZING, EnumSet.of(AWAITING_PLAN_OK, CANCELLED, FAILED));
        spec.put(AWAITING_PLAN_OK, EnumSet.of(TESTING, CANCELLED));
        spec.put(TESTING, EnumSet.of(INVESTIGATING, CANCELLED, FAILED));
        spec.put(INVESTIGATING, EnumSet.of(AWAITING_FIX_OK, DONE, CANCELLED, FAILED));
        spec.put(AWAITING_FIX_OK, EnumSet.of(DONE, CANCELLED));
        spec.put(DONE, EnumSet.noneOf(RunStatus.class));
        spec.put(FAILED, EnumSet.noneOf(RunStatus.class));
        spec.put(CANCELLED, EnumSet.noneOf(RunStatus.class));
        return spec;
    }

    /** Every (from, to) pair, 9 x 9 = 81, with the expected verdict. */
    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                pairs.add(Arguments.of(from, to, SPEC.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("allPairs")
    void everyPairMatchesTheSpecification(
            RunStatus from, RunStatus to, boolean expected) {
        assertEquals(expected, RunTransitions.isAllowed(from, to),
                from + " -> " + to);
    }

    @Test
    void specificationCoversEveryStatus() {
        // Guards the exhaustive test: a new status must be added to SPEC.
        assertEquals(EnumSet.allOf(RunStatus.class), SPEC.keySet());
        assertEquals(81, allPairs().count());
    }

    @Test
    void exactlySeventeenTransitionsAreAllowed() {
        long allowed = allPairs()
                .filter(a -> (Boolean) a.get()[2])
                .count();
        assertEquals(17, allowed);
    }

    @Test
    void allowedFromMatchesTheSpecification() {
        for (RunStatus from : RunStatus.values()) {
            assertEquals(SPEC.get(from), RunTransitions.allowedFrom(from), from.name());
        }
    }

    @Test
    void finalStatusesHaveNoOutgoingTransitions() {
        for (RunStatus from : EnumSet.of(DONE, FAILED, CANCELLED)) {
            assertTrue(from.isFinal());
            assertTrue(RunTransitions.allowedFrom(from).isEmpty());
        }
    }

    @Test
    void statusClassesArePartitioned() {
        assertEquals(EnumSet.of(DONE, FAILED, CANCELLED),
                statusesWhere(RunStatus::isFinal));
        assertEquals(EnumSet.of(ANALYZING, TESTING, INVESTIGATING),
                statusesWhere(RunStatus::isActive));
        assertEquals(EnumSet.of(AWAITING_PLAN_OK, AWAITING_FIX_OK),
                statusesWhere(RunStatus::isAwaiting));
        assertFalse(QUEUED.isFinal() || QUEUED.isActive() || QUEUED.isAwaiting());
    }

    @Test
    void nullsAreNeverAllowed() {
        assertFalse(RunTransitions.isAllowed(null, QUEUED));
        assertFalse(RunTransitions.isAllowed(QUEUED, null));
    }

    private static Set<RunStatus> statusesWhere(
            java.util.function.Predicate<RunStatus> predicate) {
        Set<RunStatus> result = EnumSet.noneOf(RunStatus.class);
        for (RunStatus status : RunStatus.values()) {
            if (predicate.test(status)) {
                result.add(status);
            }
        }
        return result;
    }
}

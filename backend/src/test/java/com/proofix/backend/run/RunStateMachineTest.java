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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.proofix.backend.exception.ConflictException;
import com.proofix.backend.exception.ResourceNotFoundException;

class RunStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();
    private static final RunBudgets BUDGETS = new RunBudgets(1, 50, 100);

    /** Legal path from QUEUED to each status; fixtures never bypass the machine. */
    private static final Map<RunStatus, List<RunStatus>> PATH = paths();

    private MutableClock clock;
    private RunRepository runRepository;
    private RunEventRepository eventRepository;
    private RunStateMachine machine;

    private Run current;
    private final List<RunEvent> events = new ArrayList<>();
    private int saves;

    private static Map<RunStatus, List<RunStatus>> paths() {
        Map<RunStatus, List<RunStatus>> p = new EnumMap<>(RunStatus.class);
        p.put(QUEUED, List.of());
        p.put(ANALYZING, List.of(ANALYZING));
        p.put(AWAITING_PLAN_OK, List.of(ANALYZING, AWAITING_PLAN_OK));
        p.put(TESTING, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING));
        p.put(INVESTIGATING, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING));
        p.put(AWAITING_FIX_OK, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING,
                AWAITING_FIX_OK));
        p.put(DONE, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING, DONE));
        p.put(FAILED, List.of(FAILED));
        p.put(CANCELLED, List.of(CANCELLED));
        return p;
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        runRepository = mock(RunRepository.class);
        eventRepository = mock(RunEventRepository.class);

        lenient().when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            saves++;
            current = inv.getArgument(0);
            return current;
        });
        lenient().when(runRepository.findByIdForUpdate(any()))
                .thenAnswer(inv -> Optional.ofNullable(current));
        lenient().when(eventRepository.save(any(RunEvent.class))).thenAnswer(inv -> {
            RunEvent event = inv.getArgument(0);
            events.add(event);
            return event;
        });

        machine = new RunStateMachine(runRepository, eventRepository, clock);
    }

    private Run newRun() {
        return machine.create(OWNER, "https://github.com/octo/demo", BUDGETS);
    }

    private Run walkTo(RunStatus target) {
        Run run = newRun();
        for (RunStatus step : PATH.get(target)) {
            run = machine.transition(run.getId(), step, RunActor.SYSTEM, "fixture");
        }
        assertEquals(target, run.getStatus());
        return run;
    }

    // ------------------------------------------------------------------
    // Exhaustive: every (from, to) pair through the one mutating method
    // ------------------------------------------------------------------

    static Stream<Arguments> allPairs() {
        return RunTransitionsTest.allPairs();
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("allPairs")
    void transitionEnforcesTheTableForEveryPair(
            RunStatus from, RunStatus to, boolean allowed) {
        Run run = walkTo(from);
        int eventsBefore = events.size();
        int savesBefore = saves;

        if (allowed) {
            Run moved = machine.transition(run.getId(), to, RunActor.WORKER, "step");
            assertEquals(to, moved.getStatus());
            assertEquals(eventsBefore + 1, events.size());
            RunEvent event = events.get(events.size() - 1);
            assertEquals(from, event.getFromStatus());
            assertEquals(to, event.getToStatus());
            assertEquals(RunActor.WORKER, event.getActor());
        } else {
            IllegalRunTransitionException ex = assertThrows(
                    IllegalRunTransitionException.class,
                    () -> machine.transition(run.getId(), to, RunActor.WORKER, "step"));
            assertInstanceOf(ConflictException.class, ex);
            assertTrue(ex.getMessage().contains(from.name()));
            assertTrue(ex.getMessage().contains(to.name()));
            assertEquals(from, run.getStatus());
            assertEquals(eventsBefore, events.size());
            assertEquals(savesBefore, saves);
        }
    }

    // ------------------------------------------------------------------
    // Events and bookkeeping
    // ------------------------------------------------------------------

    @Test
    void createStartsQueuedAndRecordsTheCreationEvent() {
        Run run = newRun();

        assertEquals(QUEUED, run.getStatus());
        assertEquals(OWNER, run.getOwnerUserId());
        assertEquals("https://github.com/octo/demo", run.getRepoUrl());
        assertEquals(1, run.getMaxMinutes());
        assertEquals(50, run.getMaxGeminiCalls());
        assertEquals(100, run.getMaxUsers());
        assertFalse(run.isCancelRequested());
        assertEquals(0, run.getActiveMs());
        assertNull(run.getPhaseStartedAt());
        assertNull(run.getFinishedAt());
        assertEquals(at(T0), run.getCreatedAt());
        assertEquals(at(T0), run.getUpdatedAt());

        assertEquals(1, events.size());
        RunEvent created = events.get(0);
        assertNull(created.getFromStatus());
        assertEquals(QUEUED, created.getToStatus());
        assertEquals(RunActor.USER, created.getActor());
        assertEquals(at(T0), created.getCreatedAt());
    }

    @Test
    void eventRecordsNoteActorAndTime() {
        Run run = newRun();
        clock.advance(Duration.ofSeconds(5));

        machine.transition(run.getId(), ANALYZING, RunActor.WORKER, "claimed");

        RunEvent event = events.get(events.size() - 1);
        assertEquals(QUEUED, event.getFromStatus());
        assertEquals(ANALYZING, event.getToStatus());
        assertEquals(RunActor.WORKER, event.getActor());
        assertEquals("claimed", event.getNote());
        assertEquals(at(T0.plusSeconds(5)), event.getCreatedAt());
        assertEquals(at(T0.plusSeconds(5)), run.getUpdatedAt());
    }

    @Test
    void finalStatusesSetFinishedAtAndOthersDoNot() {
        for (RunStatus status : RunStatus.values()) {
            Run run = walkTo(status);
            if (status.isFinal()) {
                assertNotNull(run.getFinishedAt(), status.name());
            } else {
                assertNull(run.getFinishedAt(), status.name());
            }
        }
    }

    @Test
    void failedRequiresAReasonAndStoresIt() {
        Run run = newRun();

        assertThrows(IllegalArgumentException.class,
                () -> machine.transition(run.getId(), FAILED, RunActor.SYSTEM, " "));
        assertEquals(QUEUED, run.getStatus());

        machine.transition(run.getId(), FAILED, RunActor.SYSTEM, "worker heartbeat lost");
        assertEquals("worker heartbeat lost", run.getFailureReason());
    }

    @Test
    void leavingActiveWorkClearsTheRunner() {
        Run run = walkTo(ANALYZING);
        run.setRunnerId("worker-1");

        machine.transition(run.getId(), AWAITING_PLAN_OK, RunActor.WORKER, null);

        assertNull(run.getRunnerId());
    }

    @Test
    void activeToActiveKeepsTheRunner() {
        Run run = walkTo(TESTING);
        run.setRunnerId("worker-1");

        machine.transition(run.getId(), INVESTIGATING, RunActor.WORKER, null);

        assertEquals("worker-1", run.getRunnerId());
    }

    @Test
    void unknownRunIsNotFound() {
        assertThrows(ResourceNotFoundException.class,
                () -> machine.transition(UUID.randomUUID(), ANALYZING, RunActor.WORKER, null));
    }

    @Test
    void actorIsRequired() {
        Run run = newRun();
        assertThrows(IllegalArgumentException.class,
                () -> machine.transition(run.getId(), ANALYZING, null, null));
        assertEquals(QUEUED, run.getStatus());
    }

    // ------------------------------------------------------------------
    // max_minutes accounting: worker time only
    // ------------------------------------------------------------------

    @Test
    void timeInQueueDoesNotCount() {
        Run run = newRun();
        clock.advance(Duration.ofHours(2));

        machine.transition(run.getId(), ANALYZING, RunActor.WORKER, null);

        assertEquals(0, run.getActiveMs());
        assertEquals(at(clock.instant()), run.getPhaseStartedAt());
        assertEquals(0, run.activeMillis(clock.instant()));
    }

    @Test
    void timeInAwaitingStatesDoesNotCount() {
        Run run = walkTo(ANALYZING);
        clock.advance(Duration.ofSeconds(20));
        machine.transition(run.getId(), AWAITING_PLAN_OK, RunActor.WORKER, null);

        assertEquals(20_000, run.getActiveMs());
        assertNull(run.getPhaseStartedAt());

        // Three days waiting for the human: the clock stops.
        clock.advance(Duration.ofDays(3));
        assertEquals(20_000, run.activeMillis(clock.instant()));
        assertFalse(run.exceedsMaxMinutes(clock.instant()));

        machine.transition(run.getId(), TESTING, RunActor.USER, "approved");
        assertEquals(20_000, run.activeMillis(clock.instant()));
        assertEquals(at(clock.instant()), run.getPhaseStartedAt());
    }

    @Test
    void pauseAndResumeAccountingAcrossTheWholeRun() {
        Run run = newRun();
        clock.advance(Duration.ofMinutes(10));             // queued: not counted
        machine.transition(run.getId(), ANALYZING, RunActor.WORKER, null);
        clock.advance(Duration.ofSeconds(7));              // +7s
        assertEquals(7_000, run.activeMillis(clock.instant()));

        machine.transition(run.getId(), AWAITING_PLAN_OK, RunActor.WORKER, null);
        clock.advance(Duration.ofHours(5));                // paused
        machine.transition(run.getId(), TESTING, RunActor.USER, "approved");
        clock.advance(Duration.ofSeconds(11));             // +11s
        machine.transition(run.getId(), INVESTIGATING, RunActor.WORKER, null);
        assertEquals(18_000, run.getActiveMs());           // active -> active banks time
        assertEquals(at(clock.instant()), run.getPhaseStartedAt());

        clock.advance(Duration.ofSeconds(13));             // +13s
        assertEquals(31_000, run.activeMillis(clock.instant()));
        machine.transition(run.getId(), AWAITING_FIX_OK, RunActor.WORKER, null);
        clock.advance(Duration.ofDays(1));                 // paused
        machine.transition(run.getId(), DONE, RunActor.USER, "approved");

        assertEquals(31_000, run.getActiveMs());
        assertNull(run.getPhaseStartedAt());
        assertEquals(31_000, run.activeMillis(clock.instant().plusSeconds(999)));
    }

    @Test
    void failingFromAnActiveStateBanksTheTime() {
        Run run = walkTo(ANALYZING);
        clock.advance(Duration.ofSeconds(4));

        machine.transition(run.getId(), FAILED, RunActor.SYSTEM, "boom");

        assertEquals(4_000, run.getActiveMs());
        assertNull(run.getPhaseStartedAt());
    }

    @Test
    void maxMinutesBoundaryIsStrictlyGreaterThan() {
        Run run = walkTo(ANALYZING);                       // max_minutes = 1

        clock.advance(Duration.ofMillis(60_000));
        assertFalse(run.exceedsMaxMinutes(clock.instant()));

        clock.advance(Duration.ofMillis(1));
        assertTrue(run.exceedsMaxMinutes(clock.instant()));
    }

    @Test
    void approvedButUnclaimedTimeCounts() {
        // Approval moves the Run straight into TESTING with no runner; that
        // waiting time is active time, so the reaper can enforce max_minutes.
        Run run = walkTo(AWAITING_PLAN_OK);
        machine.transition(run.getId(), TESTING, RunActor.USER, "approved");
        assertNull(run.getRunnerId());

        clock.advance(Duration.ofSeconds(61));
        assertTrue(run.exceedsMaxMinutes(clock.instant()));
    }

    @Test
    void budgetsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RunBudgets(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RunBudgets(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RunBudgets(1, 1, 0));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

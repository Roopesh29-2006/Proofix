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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.proofix.backend.exception.RepositoryAccessDeniedException;
import com.proofix.backend.run.RunTestUsers.TestUser;
import com.proofix.backend.security.JwtService;
import com.proofix.backend.service.RepositoryAnalysisService;

/**
 * /api/runs through the real security chain, against proofix_test. GitHub is
 * mocked. Data is committed (requests run in their own transactions) and
 * removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunApiIntegrationTest {

    private static final String REPO = "https://github.com/octo/demo";

    private static final Map<RunStatus, List<RunStatus>> PATH = new EnumMap<>(Map.of(
            QUEUED, List.of(),
            ANALYZING, List.of(ANALYZING),
            AWAITING_PLAN_OK, List.of(ANALYZING, AWAITING_PLAN_OK),
            TESTING, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING),
            INVESTIGATING, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING),
            AWAITING_FIX_OK, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING,
                    AWAITING_FIX_OK),
            DONE, List.of(ANALYZING, AWAITING_PLAN_OK, TESTING, INVESTIGATING, DONE),
            FAILED, List.of(FAILED),
            CANCELLED, List.of(CANCELLED)));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunStateMachine machine;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private RepositoryAnalysisService repositoryAnalysisService;

    private RunTestUsers users;
    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void setUp() {
        users = new RunTestUsers(jdbc, jwtService);
        alice = users.create();
        bob = users.create();
    }

    @AfterEach
    void tearDown() {
        users.deleteAll();
        reset(repositoryAnalysisService);
    }

    // ------------------------------------------------------------------
    // Fixtures: always reach a state through legal transitions
    // ------------------------------------------------------------------

    private Run runIn(TestUser owner, RunStatus status) {
        Run run = machine.create(owner.id(), REPO, new RunBudgets(30, 50, 100));
        for (RunStatus step : PATH.get(status)) {
            run = machine.transition(run.getId(), step, RunActor.SYSTEM, "fixture");
        }
        return run;
    }

    private Run activeWithRunner(TestUser owner, RunStatus status) {
        Run run = runIn(owner, status);
        run.setRunnerId("worker-1");
        return runRepository.save(run);
    }

    private Run reload(Run run) {
        return runRepository.findById(run.getId()).orElseThrow();
    }

    private ResultActions postAs(TestUser user, String path) throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()));
    }

    private ResultActions getAs(TestUser user, String path) throws Exception {
        return mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()));
    }

    private ResultActions createAs(TestUser user, String repoUrl) throws Exception {
        return mockMvc.perform(post("/api/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"repoUrl\": \"" + repoUrl + "\"}"));
    }

    // ------------------------------------------------------------------
    // POST /api/runs
    // ------------------------------------------------------------------

    @Test
    void createReturns201WithQueuedRunAndDefaultBudgets() throws Exception {
        createAs(alice, "https://github.com/Octo/Demo")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/runs/")))
                .andExpect(jsonPath("$.status", is("QUEUED")))
                .andExpect(jsonPath("$.repoUrl", is(REPO)))
                .andExpect(jsonPath("$.cancelRequested", is(false)))
                .andExpect(jsonPath("$.maxMinutes", is(30)))
                .andExpect(jsonPath("$.maxGeminiCalls", is(50)))
                .andExpect(jsonPath("$.maxUsers", is(100)))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        verify(repositoryAnalysisService).verifyPublicRepository(REPO);
        assertEquals(1, users.runCount(alice.id()));
    }

    @Test
    void responsesNeverExposeRunnerOrHeartbeat() throws Exception {
        Run run = activeWithRunner(alice, ANALYZING);
        run.setHeartbeatAt(run.getUpdatedAt());
        runRepository.save(run);

        getAs(alice, "/api/runs/" + run.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnerId").doesNotExist())
                .andExpect(jsonPath("$.heartbeatAt").doesNotExist())
                .andExpect(content().string(not(containsString("worker-1"))))
                .andExpect(content().string(not(containsString("runner"))))
                .andExpect(content().string(not(containsString("heartbeat"))));

        getAs(alice, "/api/runs")
                .andExpect(content().string(not(containsString("runner"))))
                .andExpect(content().string(not(containsString("heartbeat"))));

        postAs(alice, "/api/runs/" + run.getId() + "/cancel")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("runner"))))
                .andExpect(content().string(not(containsString("heartbeat"))));
    }

    @Test
    void invalidUrlIsRejectedBeforeAnyGitHubCall() throws Exception {
        createAs(alice, "https://github.com/octo/demo?tab=1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        verify(repositoryAnalysisService, never()).verifyPublicRepository(anyString());
        assertEquals(0, users.runCount(alice.id()));
    }

    @Test
    void missingRepoUrlIsRejected() throws Exception {
        mockMvc.perform(post("/api/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, users.runCount(alice.id()));
    }

    @Test
    void privateRepositoryIsRejectedAndNothingIsStored() throws Exception {
        doThrow(new RepositoryAccessDeniedException("Private repositories require a "
                + "project repository connection."))
                .when(repositoryAnalysisService).verifyPublicRepository(REPO);

        createAs(alice, REPO).andExpect(status().isForbidden());

        assertEquals(0, users.runCount(alice.id()));
    }

    @Test
    void gitHubCheckRunsWithoutAnOpenTransactionOrConnection() throws Exception {
        AtomicBoolean checked = new AtomicBoolean();
        doAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "transaction open during GitHub call");
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(),
                    "connection or EntityManager bound during GitHub call");
            checked.set(true);
            return null;
        }).when(repositoryAnalysisService).verifyPublicRepository(REPO);

        createAs(alice, REPO).andExpect(status().isCreated());

        assertTrue(checked.get());
    }

    @Test
    void unauthenticatedRequestsGet401() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\": \"" + REPO + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/runs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/runs/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/runs/" + UUID.randomUUID() + "/cancel"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Per-user cap on QUEUED + active Runs (default 3)
    // ------------------------------------------------------------------

    @Test
    void capCountsQueuedAndActiveRunsAndReturns409() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, ANALYZING);
        runIn(alice, TESTING);

        createAs(alice, REPO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("3")));

        assertEquals(3, users.runCount(alice.id()));
    }

    @Test
    void overCapRequestNeverCallsGitHub() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);

        createAs(alice, REPO).andExpect(status().isConflict());

        verify(repositoryAnalysisService, never()).verifyPublicRepository(anyString());
    }

    @Test
    void awaitingAndFinalRunsDoNotCountTowardTheCap() throws Exception {
        runIn(alice, AWAITING_PLAN_OK);
        runIn(alice, AWAITING_FIX_OK);
        runIn(alice, DONE);
        runIn(alice, FAILED);
        runIn(alice, CANCELLED);
        runIn(alice, QUEUED);
        runIn(alice, ANALYZING);

        createAs(alice, REPO).andExpect(status().isCreated());
        createAs(alice, REPO).andExpect(status().isConflict());
    }

    @Test
    void capIsPerUser() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);

        createAs(bob, REPO).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Per-user cap on unfinished Runs (QUEUED + active + AWAITING, default 10)
    // ------------------------------------------------------------------

    @Test
    void unfinishedCapCountsAwaitingRunsAndReturns409() throws Exception {
        for (int i = 0; i < 5; i++) {
            runIn(alice, AWAITING_PLAN_OK);
            runIn(alice, AWAITING_FIX_OK);
        }

        createAs(alice, REPO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("10")));

        verify(repositoryAnalysisService, never()).verifyPublicRepository(anyString());
        assertEquals(10, users.runCount(alice.id()));
    }

    @Test
    void unfinishedCapAllowsTheTenthRun() throws Exception {
        for (int i = 0; i < 9; i++) {
            runIn(alice, AWAITING_PLAN_OK);
        }

        createAs(alice, REPO).andExpect(status().isCreated());
        createAs(alice, REPO).andExpect(status().isConflict());
    }

    @Test
    void finalRunsDoNotCountTowardTheUnfinishedCap() throws Exception {
        for (int i = 0; i < 4; i++) {
            runIn(alice, DONE);
            runIn(alice, FAILED);
            runIn(alice, CANCELLED);
        }

        createAs(alice, REPO).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Approving a plan re-checks the active cap
    // ------------------------------------------------------------------

    @Test
    void approvingThePlanAtTheActiveCapIs409() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, ANALYZING);
        runIn(alice, TESTING);
        Run plan = runIn(alice, AWAITING_PLAN_OK);
        int eventsBefore = eventRepository.findByRunIdOrderByIdAsc(plan.getId()).size();

        postAs(alice, "/api/runs/" + plan.getId() + "/approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("3")));

        assertEquals(AWAITING_PLAN_OK, reload(plan).getStatus());
        assertEquals(eventsBefore, eventRepository.findByRunIdOrderByIdAsc(plan.getId()).size());
    }

    @Test
    void approvingThePlanBelowTheActiveCapSucceeds() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, TESTING);
        Run plan = runIn(alice, AWAITING_PLAN_OK);

        postAs(alice, "/api/runs/" + plan.getId() + "/approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("TESTING")));
    }

    @Test
    void approvingTheFixIsNotLimitedByTheActiveCap() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);
        Run fix = runIn(alice, AWAITING_FIX_OK);

        postAs(alice, "/api/runs/" + fix.getId() + "/approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")));
    }

    @Test
    void rejectingIsNotLimitedByTheActiveCap() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);
        runIn(alice, QUEUED);
        Run plan = runIn(alice, AWAITING_PLAN_OK);

        postAs(alice, "/api/runs/" + plan.getId() + "/reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    // ------------------------------------------------------------------
    // A deleted user with a still-valid token
    // ------------------------------------------------------------------

    @Test
    void deletedUserWithAValidTokenGetsCleanErrorsNot500() throws Exception {
        TestUser ghost = users.create();
        jdbc.update("DELETE FROM users WHERE user_id = ?", ghost.id());

        createAs(ghost, REPO)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Authentication required")));
        verify(repositoryAnalysisService, never()).verifyPublicRepository(anyString());

        UUID someRun = runIn(alice, AWAITING_PLAN_OK).getId();
        getAs(ghost, "/api/runs/" + someRun).andExpect(status().isNotFound());
        postAs(ghost, "/api/runs/" + someRun + "/cancel").andExpect(status().isNotFound());
        postAs(ghost, "/api/runs/" + someRun + "/approve").andExpect(status().isNotFound());
        postAs(ghost, "/api/runs/" + someRun + "/reject").andExpect(status().isNotFound());
        getAs(ghost, "/api/runs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        assertEquals(AWAITING_PLAN_OK, runRepository.findById(someRun).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    @Test
    void ownerCanReadTheirRun() throws Exception {
        Run run = runIn(alice, AWAITING_PLAN_OK);

        getAs(alice, "/api/runs/" + run.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(run.getId().toString())))
                .andExpect(jsonPath("$.status", is("AWAITING_PLAN_OK")));
    }

    @Test
    void listIsPagedNewestFirstAndOwnRunsOnly() throws Exception {
        Run a1 = runIn(alice, QUEUED);
        Run a2 = runIn(alice, DONE);
        Run a3 = runIn(alice, CANCELLED);
        Run b1 = runIn(bob, QUEUED);

        getAs(alice, "/api/runs?page=0&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id", is(a3.getId().toString())))
                .andExpect(jsonPath("$.items[1].id", is(a2.getId().toString())))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(2)));

        getAs(alice, "/api/runs?page=1&size=2")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(a1.getId().toString())));

        getAs(bob, "/api/runs")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(b1.getId().toString())))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        getAs(alice, "/api/runs?page=-1").andExpect(status().isBadRequest());
        getAs(alice, "/api/runs?size=0").andExpect(status().isBadRequest());
        getAs(alice, "/api/runs?size=101").andExpect(status().isBadRequest());
    }

    @Test
    void unknownRunIs404() throws Exception {
        getAs(alice, "/api/runs/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Ownership: another user's Run is indistinguishable from a missing one
    // ------------------------------------------------------------------

    @Test
    void otherUserGets404OnGetAndTheBodyMatchesAMissingRun() throws Exception {
        Run run = runIn(alice, AWAITING_PLAN_OK);

        String otherUsers = getAs(bob, "/api/runs/" + run.getId())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = getAs(bob, "/api/runs/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertEquals(missing, otherUsers);
    }

    @Test
    void otherUserGets404OnCancelAndNothingChanges() throws Exception {
        Run queued = runIn(alice, QUEUED);
        Run active = activeWithRunner(alice, ANALYZING);

        postAs(bob, "/api/runs/" + queued.getId() + "/cancel").andExpect(status().isNotFound());
        postAs(bob, "/api/runs/" + active.getId() + "/cancel").andExpect(status().isNotFound());

        assertEquals(QUEUED, reload(queued).getStatus());
        assertFalse(reload(active).isCancelRequested());
    }

    @Test
    void otherUserGets404OnApproveAndNothingChanges() throws Exception {
        Run plan = runIn(alice, AWAITING_PLAN_OK);
        Run fix = runIn(alice, AWAITING_FIX_OK);

        postAs(bob, "/api/runs/" + plan.getId() + "/approve").andExpect(status().isNotFound());
        postAs(bob, "/api/runs/" + fix.getId() + "/approve").andExpect(status().isNotFound());

        assertEquals(AWAITING_PLAN_OK, reload(plan).getStatus());
        assertEquals(AWAITING_FIX_OK, reload(fix).getStatus());
    }

    @Test
    void otherUserGets404OnRejectAndNothingChanges() throws Exception {
        Run plan = runIn(alice, AWAITING_PLAN_OK);
        Run fix = runIn(alice, AWAITING_FIX_OK);

        postAs(bob, "/api/runs/" + plan.getId() + "/reject").andExpect(status().isNotFound());
        postAs(bob, "/api/runs/" + fix.getId() + "/reject").andExpect(status().isNotFound());

        assertEquals(AWAITING_PLAN_OK, reload(plan).getStatus());
        assertEquals(AWAITING_FIX_OK, reload(fix).getStatus());
    }

    @Test
    void otherUsersRunNeverAppearsInTheirList() throws Exception {
        runIn(alice, QUEUED);
        runIn(alice, AWAITING_PLAN_OK);
        runIn(alice, DONE);

        getAs(bob, "/api/runs?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    // ------------------------------------------------------------------
    // Cancel, by state class
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"QUEUED", "AWAITING_PLAN_OK", "AWAITING_FIX_OK"})
    void cancelIsImmediateWhenNoWorkIsRunning(RunStatus status) throws Exception {
        Run run = runIn(alice, status);

        postAs(alice, "/api/runs/" + run.getId() + "/cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        RunEvent last = lastEvent(run);
        assertEquals(status, last.getFromStatus());
        assertEquals(CANCELLED, last.getToStatus());
        assertEquals(RunActor.USER, last.getActor());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"ANALYZING", "TESTING", "INVESTIGATING"})
    void cancelOnlyRequestsWhenAWorkerOwnsTheRun(RunStatus status) throws Exception {
        Run run = activeWithRunner(alice, status);
        int eventsBefore = eventRepository.findByRunIdOrderByIdAsc(run.getId()).size();

        postAs(alice, "/api/runs/" + run.getId() + "/cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(status.name())))
                .andExpect(jsonPath("$.cancelRequested", is(true)));

        // Idempotent.
        postAs(alice, "/api/runs/" + run.getId() + "/cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelRequested", is(true)));

        Run stored = reload(run);
        assertEquals(status, stored.getStatus());
        assertTrue(stored.isCancelRequested());
        assertEquals(eventsBefore, eventRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"ANALYZING", "TESTING", "INVESTIGATING"})
    void cancelIsImmediateInActiveStateWithoutARunner(RunStatus status) throws Exception {
        Run run = runIn(alice, status);
        assertNull(reload(run).getRunnerId());

        postAs(alice, "/api/runs/" + run.getId() + "/cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        assertEquals(CANCELLED, lastEvent(run).getToStatus());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"DONE", "FAILED", "CANCELLED"})
    void cancelInAFinalStateIs409(RunStatus status) throws Exception {
        Run run = runIn(alice, status);

        postAs(alice, "/api/runs/" + run.getId() + "/cancel")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());

        Run stored = reload(run);
        assertEquals(status, stored.getStatus());
        assertFalse(stored.isCancelRequested());
    }

    // ------------------------------------------------------------------
    // Approve / reject
    // ------------------------------------------------------------------

    @Test
    void approvingThePlanMovesToTestingWithNoRunner() throws Exception {
        Run run = runIn(alice, AWAITING_PLAN_OK);

        postAs(alice, "/api/runs/" + run.getId() + "/approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("TESTING")));

        Run stored = reload(run);
        assertNull(stored.getRunnerId());
        assertNotNull(stored.getPhaseStartedAt());
        assertEquals(RunActor.USER, lastEvent(run).getActor());
    }

    @Test
    void approvingTheFixMovesToDone() throws Exception {
        Run run = runIn(alice, AWAITING_FIX_OK);

        postAs(alice, "/api/runs/" + run.getId() + "/approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")));

        assertEquals(RunActor.USER, lastEvent(run).getActor());
    }

    @Test
    void rejectingThePlanCancels() throws Exception {
        Run run = runIn(alice, AWAITING_PLAN_OK);

        postAs(alice, "/api/runs/" + run.getId() + "/reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));

        RunEvent last = lastEvent(run);
        assertEquals(CANCELLED, last.getToStatus());
        assertEquals("rejected by user", last.getNote());
        assertEquals(RunActor.USER, last.getActor());
    }

    @Test
    void rejectingTheFixFinishesAsDone() throws Exception {
        Run run = runIn(alice, AWAITING_FIX_OK);

        postAs(alice, "/api/runs/" + run.getId() + "/reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")));

        RunEvent last = lastEvent(run);
        assertEquals(DONE, last.getToStatus());
        assertEquals("fix declined by user", last.getNote());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"AWAITING_PLAN_OK", "AWAITING_FIX_OK"})
    void approveOutsideAwaitingStatesIs409(RunStatus status) throws Exception {
        Run run = runIn(alice, status);
        int eventsBefore = eventRepository.findByRunIdOrderByIdAsc(run.getId()).size();

        postAs(alice, "/api/runs/" + run.getId() + "/approve").andExpect(status().isConflict());

        assertEquals(status, reload(run).getStatus());
        assertEquals(eventsBefore, eventRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"AWAITING_PLAN_OK", "AWAITING_FIX_OK"})
    void rejectOutsideAwaitingStatesIs409(RunStatus status) throws Exception {
        Run run = runIn(alice, status);

        postAs(alice, "/api/runs/" + run.getId() + "/reject").andExpect(status().isConflict());

        assertEquals(status, reload(run).getStatus());
    }

    private RunEvent lastEvent(Run run) {
        List<RunEvent> events = eventRepository.findByRunIdOrderByIdAsc(run.getId());
        return events.get(events.size() - 1);
    }
}

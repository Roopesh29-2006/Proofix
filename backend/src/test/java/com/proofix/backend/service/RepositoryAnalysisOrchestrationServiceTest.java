package com.proofix.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.proofix.backend.entity.Project;
import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.repository.ProjectRepository;
import com.proofix.backend.repository.ProjectRepositoryConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepositoryAnalysisOrchestrationServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROJECT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String STORED_URL =
            "https://github.com/acme/stored";

    @Mock
    private RepositoryAnalysisService repositoryAnalysisService;

    @Mock
    private RepositorySnapshotPersistenceService persistenceService;

    @Mock
    private ProjectRepositoryConnectionRepository connectionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private RepositoryAnalysisService.AnalysisResult analysisResult;

    @Mock
    private ProjectRepositoryConnection connection;

    private RepositoryAnalysisOrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new RepositoryAnalysisOrchestrationService(
                repositoryAnalysisService,
                persistenceService,
                connectionRepository,
                projectRepository);
    }

    @Test
    void urlOnlyAnalysisDoesNotRequireConnectionOrPersist() {
        when(repositoryAnalysisService.analyze(STORED_URL))
                .thenReturn(analysisResult);

        RepositoryAnalysisOrchestrationService.AnalysisOutcome outcome =
                service.analyze(STORED_URL, null, USER_ID);

        assertSame(analysisResult, outcome.result());
        assertNull(outcome.snapshotId());
        verify(repositoryAnalysisService).analyze(STORED_URL);
        verify(connectionRepository, never())
                .findByRepositoryConnectionIdAndUserId(CONNECTION_ID, USER_ID);
        verify(persistenceService, never()).persist(connection, analysisResult);
    }

    @Test
    void registeredAnalysisUsesOwnedStoredUrlAndReturnsSnapshotId() {
        RepositorySnapshot snapshot = new RepositorySnapshot();
        setSnapshotId(snapshot, SNAPSHOT_ID);
        givenOwnedActiveConnection();
        when(repositoryAnalysisService.analyze(STORED_URL))
                .thenReturn(analysisResult);
        when(persistenceService.persist(connection, analysisResult))
                .thenReturn(snapshot);

        RepositoryAnalysisOrchestrationService.AnalysisOutcome outcome =
                service.analyze(null, CONNECTION_ID, USER_ID);

        assertEquals(SNAPSHOT_ID, outcome.snapshotId());
        verify(repositoryAnalysisService).analyze(STORED_URL);
        verify(persistenceService).persist(connection, analysisResult);
    }

    @Test
    void unauthorizedConnectionIsNotRevealed() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.analyze(null, CONNECTION_ID, USER_ID));

        verify(repositoryAnalysisService, never()).analyze(STORED_URL);
        verify(persistenceService, never()).persist(connection, analysisResult);
    }

    @Test
    void revokedConnectionIsRejected() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getRevokedAt()).thenReturn(
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        assertThrows(ResourceNotFoundException.class, () ->
                service.analyze(null, CONNECTION_ID, USER_ID));

        verify(repositoryAnalysisService, never()).analyze(STORED_URL);
        verify(projectRepository, never())
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void missingProjectIdCannotTriggerGithubAnalysis() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getRevokedAt()).thenReturn(null);
        when(connection.getProjectId()).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () ->
                service.analyze(null, CONNECTION_ID, USER_ID));

        verify(repositoryAnalysisService, never()).analyze(STORED_URL);
    }

    @Test
    void projectOwnershipLookupUsesAuthenticatedUserId() {
        givenOwnedActiveConnection();
        when(repositoryAnalysisService.analyze(STORED_URL))
                .thenReturn(analysisResult);
        RepositorySnapshot snapshot = new RepositorySnapshot();
        setSnapshotId(snapshot, SNAPSHOT_ID);
        when(persistenceService.persist(connection, analysisResult))
                .thenReturn(snapshot);

        service.analyze(null, CONNECTION_ID, USER_ID);

        verify(projectRepository).findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID);
    }

    @Test
    void githubFailureDoesNotInvokePersistence() {
        givenOwnedActiveConnection();
        RuntimeException failure = new RuntimeException("GitHub failure");
        when(repositoryAnalysisService.analyze(STORED_URL))
                .thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () ->
                service.analyze(null, CONNECTION_ID, USER_ID)));

        verify(persistenceService, never()).persist(connection, analysisResult);
    }

    @Test
    void persistenceFailureDoesNotReturnOutcome() {
        givenOwnedActiveConnection();
        when(repositoryAnalysisService.analyze(STORED_URL))
                .thenReturn(analysisResult);
        RuntimeException failure = new RuntimeException("Persistence failure");
        when(persistenceService.persist(connection, analysisResult))
                .thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () ->
                service.analyze(null, CONNECTION_ID, USER_ID)));
    }

    private void givenOwnedActiveConnection() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getProjectId()).thenReturn(PROJECT_ID);
        when(connection.getRevokedAt()).thenReturn(null);
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.of(new Project()));
        when(connection.getRepositoryUrl()).thenReturn(STORED_URL);
    }

    private static void setSnapshotId(
            RepositorySnapshot snapshot,
            UUID snapshotId) {
        try {
            var field = RepositorySnapshot.class
                    .getDeclaredField("snapshotId");
            field.setAccessible(true);
            field.set(snapshot, snapshotId);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}

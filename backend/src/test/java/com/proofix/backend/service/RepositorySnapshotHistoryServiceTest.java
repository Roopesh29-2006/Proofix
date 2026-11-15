package com.proofix.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.proofix.backend.entity.Project;
import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.repository.ProjectRepository;
import com.proofix.backend.repository.ProjectRepositoryConnectionRepository;
import com.proofix.backend.repository.RepositorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RepositorySnapshotHistoryServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROJECT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private ProjectRepositoryConnectionRepository connectionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private RepositorySnapshotRepository snapshotRepository;

    @Mock
    private ProjectRepositoryConnection connection;

    private RepositorySnapshotHistoryService service;

    @BeforeEach
    void setUp() {
        service = new RepositorySnapshotHistoryService(
                connectionRepository, projectRepository, snapshotRepository);
    }

    @Test
    void returnsNewestFirstPaginatedMetadata() {
        givenActiveOwnedConnection();
        RepositorySnapshot newest = snapshot("newest");
        RepositorySnapshot older = snapshot("older");
        PageRequest pageable = PageRequest.of(1, 2);
        when(snapshotRepository
                .findAllByRepositoryConnectionIdOrderByAnalyzedAtDescSnapshotIdDesc(
                        CONNECTION_ID, pageable))
                .thenReturn(new PageImpl<>(
                        List.of(newest, older), pageable, 5));

        var response = service.getHistory(
                CONNECTION_ID, USER_ID, pageable);

        assertEquals(2, response.getContent().size());
        assertEquals("newest", response.getContent().get(0).commitSha());
        assertEquals("older", response.getContent().get(1).commitSha());
        assertEquals(1, response.getPage());
        assertEquals(2, response.getSize());
        assertEquals(5, response.getTotalElements());
        assertEquals(3, response.getTotalPages());
        assertEquals(
                pageable,
                PageRequest.of(1, 2),
                "pagination must be delegated to the repository");
        verify(connectionRepository)
                .findByRepositoryConnectionIdAndUserId(
                        CONNECTION_ID, USER_ID);
        verify(snapshotRepository)
                .findAllByRepositoryConnectionIdOrderByAnalyzedAtDescSnapshotIdDesc(
                        CONNECTION_ID, pageable);
    }

    @Test
    void rejectsAnotherUsersConnection() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getHistory(
                        CONNECTION_ID, USER_ID, PageRequest.of(0, 10)));

        verify(snapshotRepository, never())
                .findAllByRepositoryConnectionIdOrderByAnalyzedAtDescSnapshotIdDesc(
                        CONNECTION_ID, PageRequest.of(0, 10));
    }

    @Test
    void rejectsRevokedConnectionBeforeProjectLookup() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getRevokedAt()).thenReturn(
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        assertThrows(ResourceNotFoundException.class, () ->
                service.getHistory(
                        CONNECTION_ID, USER_ID, PageRequest.of(0, 10)));

        verify(projectRepository, never())
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void rejectsDeletedProject() {
        givenOwnedConnection();
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getHistory(
                        CONNECTION_ID, USER_ID, PageRequest.of(0, 10)));

        verify(snapshotRepository, never())
                .findAllByRepositoryConnectionIdOrderByAnalyzedAtDescSnapshotIdDesc(
                        CONNECTION_ID, PageRequest.of(0, 10));
    }

    @Test
    void rejectsPageSizeAboveMaximum() {
        assertThrows(BadRequestException.class, () ->
                service.getHistory(
                        CONNECTION_ID, USER_ID, PageRequest.of(0, 51)));

        verify(connectionRepository, never())
                .findByRepositoryConnectionIdAndUserId(
                        CONNECTION_ID, USER_ID);
    }

    @Test
    void returnsOwnedSnapshotMetadata() {
        givenActiveOwnedConnection();
        RepositorySnapshot snapshot = snapshot("commit-sha");
        snapshot.setRepositoryConnectionId(CONNECTION_ID);
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot));

        var metadata = service.getSnapshot(SNAPSHOT_ID, USER_ID);

        assertEquals("commit-sha", metadata.commitSha());
        assertEquals("main", metadata.branchName());
        verify(connectionRepository)
                .findByRepositoryConnectionIdAndUserId(
                        CONNECTION_ID, USER_ID);
        verify(projectRepository)
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void unknownSnapshotIsNotFoundBeforeOwnershipLookup() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getSnapshot(SNAPSHOT_ID, USER_ID));

        verify(connectionRepository, never())
                .findByRepositoryConnectionIdAndUserId(
                        CONNECTION_ID, USER_ID);
    }

    @Test
    void snapshotForAnotherUserIsNotFound() {
        RepositorySnapshot snapshot = snapshot("commit-sha");
        snapshot.setRepositoryConnectionId(CONNECTION_ID);
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot));
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getSnapshot(SNAPSHOT_ID, USER_ID));

        verify(projectRepository, never())
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void revokedSnapshotConnectionIsNotFound() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshotWithConnection()));
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getRevokedAt()).thenReturn(
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        assertThrows(ResourceNotFoundException.class, () ->
                service.getSnapshot(SNAPSHOT_ID, USER_ID));

        verify(projectRepository, never())
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void deletedSnapshotProjectIsNotFound() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshotWithConnection()));
        givenOwnedConnection();
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getSnapshot(SNAPSHOT_ID, USER_ID));
    }

    @Test
    void databaseFailureIsPropagated() {
        RuntimeException failure = new RuntimeException("database failure");
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenThrow(failure);

        assertEquals(failure, org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> service.getSnapshot(SNAPSHOT_ID, USER_ID)));
    }

    private void givenActiveOwnedConnection() {
        givenOwnedConnection();
        when(connection.getRevokedAt()).thenReturn(null);
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.of(new Project()));
    }

    private void givenOwnedConnection() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getProjectId()).thenReturn(PROJECT_ID);
    }

    private static RepositorySnapshot snapshot(String commitSha) {
        RepositorySnapshot snapshot = new RepositorySnapshot();
        snapshot.setCommitSha(commitSha);
        snapshot.setBranchName("main");
        snapshot.setFileCount(2);
        snapshot.setBytesDownloaded(10);
        return snapshot;
    }

    private static RepositorySnapshot snapshotWithConnection() {
        RepositorySnapshot snapshot = snapshot("commit-sha");
        snapshot.setRepositoryConnectionId(CONNECTION_ID);
        return snapshot;
    }
}

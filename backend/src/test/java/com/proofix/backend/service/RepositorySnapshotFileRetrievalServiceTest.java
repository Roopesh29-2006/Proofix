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
import com.proofix.backend.entity.RepositorySnapshotFile;
import com.proofix.backend.exception.BadRequestException;
import com.proofix.backend.exception.ResourceNotFoundException;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.repository.ProjectRepository;
import com.proofix.backend.repository.ProjectRepositoryConnectionRepository;
import com.proofix.backend.repository.RepositorySnapshotFileRepository;
import com.proofix.backend.repository.RepositorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RepositorySnapshotFileRetrievalServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONNECTION_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PROJECT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private RepositorySnapshotRepository snapshotRepository;

    @Mock
    private RepositorySnapshotFileRepository snapshotFileRepository;

    @Mock
    private ProjectRepositoryConnectionRepository connectionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectRepositoryConnection connection;

    private RepositorySnapshotFileRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RepositorySnapshotFileRetrievalService(
                snapshotRepository,
                snapshotFileRepository,
                connectionRepository,
                projectRepository);
    }

    @Test
    void retrievesUnfilteredFilesWithDatabasePagination() {
        givenActiveSnapshot();
        PageRequest pageable = PageRequest.of(1, 2);
        RepositorySnapshotFile file = file(
                "src/App.java", "blob", FileCategory.SOURCE, "redacted");
        when(snapshotFileRepository
                .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(file), pageable, 3));

        var response = service.getFiles(
                SNAPSHOT_ID, USER_ID, pageable, null, false);

        assertEquals(1, response.getContent().size());
        assertEquals("src/App.java",
                response.getContent().get(0).filePath());
        assertEquals(1, response.getPage());
        assertEquals(2, response.getSize());
        assertEquals(3, response.getTotalElements());
        assertEquals(2, response.getTotalPages());
        assertEquals(null, response.getContent().get(0).scannedContent());
        verify(snapshotFileRepository)
                .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, pageable);
    }

    @Test
    void retrievesCategoryFilteredFiles() {
        givenActiveSnapshot();
        PageRequest pageable = PageRequest.of(0, 10);
        when(snapshotFileRepository
                .findBySnapshotIdAndFileCategoryOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, FileCategory.CONFIGURATION, pageable))
                .thenReturn(new PageImpl<>(
                        List.of(file(
                                "application.yml",
                                "blob",
                                FileCategory.CONFIGURATION,
                                null)),
                        pageable,
                        1));

        var response = service.getFiles(
                SNAPSHOT_ID,
                USER_ID,
                pageable,
                FileCategory.CONFIGURATION,
                false);

        assertEquals(FileCategory.CONFIGURATION,
                response.getContent().get(0).fileCategory());
        verify(snapshotFileRepository)
                .findBySnapshotIdAndFileCategoryOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, FileCategory.CONFIGURATION, pageable);
    }

    @Test
    void includesPersistedContentForBlobWhenRequested() {
        givenActiveSnapshot();
        PageRequest pageable = PageRequest.of(0, 10);
        when(snapshotFileRepository
                .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(file(
                        "src/App.java",
                        "blob",
                        FileCategory.SOURCE,
                        "redacted content")), pageable, 1));

        var response = service.getFiles(
                SNAPSHOT_ID, USER_ID, pageable, null, true);

        assertEquals("redacted content",
                response.getContent().get(0).scannedContent());
    }

    @Test
    void neverIncludesDirectoryContent() {
        givenActiveSnapshot();
        PageRequest pageable = PageRequest.of(0, 10);
        when(snapshotFileRepository
                .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(file(
                        "src",
                        "tree",
                        FileCategory.OTHER,
                        "should not be returned")), pageable, 1));

        var response = service.getFiles(
                SNAPSHOT_ID, USER_ID, pageable, null, true);

        assertEquals(null, response.getContent().get(0).scannedContent());
    }

    @Test
    void neverInventsContentForUnscannedFile() {
        givenActiveSnapshot();
        PageRequest pageable = PageRequest.of(0, 10);
        when(snapshotFileRepository
                .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, pageable))
                .thenReturn(new PageImpl<>(List.of(file(
                        "README.md",
                        "blob",
                        FileCategory.OTHER,
                        null)), pageable, 1));

        var response = service.getFiles(
                SNAPSHOT_ID, USER_ID, pageable, null, true);

        assertEquals(null, response.getContent().get(0).scannedContent());
    }

    @Test
    void unknownSnapshotReturnsNotFound() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 10),
                        null,
                        false));

        verify(connectionRepository, never())
                .findByRepositoryConnectionIdAndUserId(
                        CONNECTION_ID, USER_ID);
    }

    @Test
    void anotherUsersSnapshotReturnsNotFound() {
        givenSnapshot();
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 10),
                        null,
                        false));

        verify(snapshotFileRepository, never())
                .findBySnapshotIdOrderByFilePathAscSnapshotFileIdAsc(
                        SNAPSHOT_ID, PageRequest.of(0, 10));
    }

    @Test
    void revokedConnectionReturnsNotFound() {
        givenSnapshot();
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getRevokedAt()).thenReturn(
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        assertThrows(ResourceNotFoundException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 10),
                        null,
                        false));

        verify(projectRepository, never())
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void deletedProjectReturnsNotFound() {
        givenOwnedConnection();
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 10),
                        null,
                        false));
    }

    @Test
    void nullProjectIdReturnsNotFound() {
        givenSnapshot();
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getProjectId()).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 10),
                        null,
                        false));

        verify(projectRepository, never())
                .findByProjectIdAndUserIdAndDeletedAtIsNull(
                        PROJECT_ID, USER_ID);
    }

    @Test
    void databaseExceptionsPropagate() {
        RuntimeException failure = new RuntimeException("database failure");
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenThrow(failure);

        assertEquals(failure, assertThrows(RuntimeException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 10),
                        null,
                        false)));
    }

    @Test
    void pageSizeAboveMaximumIsRejectedBeforeDatabaseAccess() {
        assertThrows(BadRequestException.class, () ->
                service.getFiles(
                        SNAPSHOT_ID,
                        USER_ID,
                        PageRequest.of(0, 51),
                        null,
                        false));

        verify(snapshotRepository, never()).findById(SNAPSHOT_ID);
    }

    private void givenActiveSnapshot() {
        givenOwnedConnection();
        when(projectRepository.findByProjectIdAndUserIdAndDeletedAtIsNull(
                PROJECT_ID, USER_ID)).thenReturn(Optional.of(new Project()));
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot()));
    }

    private void givenOwnedConnection() {
        when(connectionRepository.findByRepositoryConnectionIdAndUserId(
                CONNECTION_ID, USER_ID)).thenReturn(Optional.of(connection));
        when(connection.getRevokedAt()).thenReturn(null);
        when(connection.getProjectId()).thenReturn(PROJECT_ID);
    }

    private void givenSnapshot() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot()));
    }

    private static RepositorySnapshot snapshot() {
        RepositorySnapshot snapshot = new RepositorySnapshot();
        snapshot.setRepositoryConnectionId(CONNECTION_ID);
        return snapshot;
    }

    private static RepositorySnapshotFile file(
            String path,
            String type,
            FileCategory category,
            String content) {
        RepositorySnapshotFile file = new RepositorySnapshotFile();
        file.setSnapshotId(SNAPSHOT_ID);
        file.setFilePath(path);
        file.setFileType(type);
        file.setFileCategory(category);
        file.setFileSize(10);
        file.setScannedContent(content);
        file.setContentHash(content == null ? null : "hash");
        file.setSecretsRedacted(content == null ? null : true);
        return file;
    }
}

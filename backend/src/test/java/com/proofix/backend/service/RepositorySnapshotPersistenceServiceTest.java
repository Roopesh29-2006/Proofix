package com.proofix.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.proofix.backend.entity.ProjectRepositoryConnection;
import com.proofix.backend.entity.RepositorySnapshot;
import com.proofix.backend.entity.RepositorySnapshotFile;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.github.RepositoryFileReader.ScannedFile;
import com.proofix.backend.repository.RepositorySnapshotFileRepository;
import com.proofix.backend.repository.RepositorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class RepositorySnapshotPersistenceServiceTest {

    private static final UUID CONNECTION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RepositorySnapshotRepository snapshotRepository;

    @Mock
    private RepositorySnapshotFileRepository snapshotFileRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private RepositorySnapshotPersistenceService service;
    private ProjectRepositoryConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(transactionManager.getTransaction(
                any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        service = new RepositorySnapshotPersistenceService(
                snapshotRepository,
                snapshotFileRepository,
                transactionManager);
        connection = connection();
    }

    @Test
    void persistsSnapshotAndAllClassifiedEntries() throws Exception {
        List<RepositorySnapshotFile> persistedFiles = new ArrayList<>();
        stubSaveOperations(persistedFiles);
        RepositoryAnalysisService.AnalysisResult analysis = analysisResult();

        RepositorySnapshot snapshot = service.persist(
                connection,
                analysis);

        assertNotNull(snapshot);
        assertEquals("commit-sha", snapshot.getCommitSha());
        assertEquals("main", snapshot.getBranchName());
        assertEquals("Initial commit", snapshot.getCommitMessage());
        assertEquals(
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                snapshot.getCommitAuthorDate());
        assertSame(analysis.metadata(), snapshot.getRepositoryMetadata());
        assertEquals(1, snapshot.getFileCount());
        assertEquals(12, snapshot.getBytesDownloaded());
        assertEquals(2, persistedFiles.size());

        var transactionDefinition =
                org.mockito.ArgumentCaptor.forClass(
                        TransactionDefinition.class);
        verify(transactionManager).getTransaction(
                transactionDefinition.capture());
        assertEquals(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                transactionDefinition.getValue().getPropagationBehavior());
    }

    @Test
    void reusesExistingSnapshotWithoutInsertingFiles() throws Exception {
        RepositorySnapshot existing = new RepositorySnapshot();
        when(snapshotRepository.findByRepositoryConnectionIdAndCommitSha(
                CONNECTION_ID,
                "commit-sha"))
                .thenReturn(Optional.of(existing));

        RepositorySnapshot result = service.persist(
                connection,
                analysisResult());

        assertSame(existing, result);
        verify(snapshotRepository, never()).save(any());
        verify(snapshotFileRepository, never()).saveAll(any());
    }

    @Test
    void matchesScannedContentByExactPathAndHashesRedactedContent() {
        List<RepositorySnapshotFile> persistedFiles = new ArrayList<>();
        stubSaveOperations(persistedFiles);

        service.persist(connection, analysisResult());

        RepositorySnapshotFile scannedFile = persistedFiles.stream()
                .filter(file -> file.getFilePath().equals("src/App.java"))
                .findFirst()
                .orElseThrow();

        assertEquals("class App {}", scannedFile.getScannedContent());
        assertEquals(
                "42f26956ba1a845d48ac0db6270beddc5d864212ef56efe8a7964871297b9da3",
                scannedFile.getContentHash());
        assertEquals(Boolean.TRUE, scannedFile.getSecretsRedacted());
    }

    @Test
    void leavesDirectoryAndUnscannedFieldsNull() {
        List<RepositorySnapshotFile> persistedFiles = new ArrayList<>();
        stubSaveOperations(persistedFiles);

        service.persist(connection, analysisResult());

        RepositorySnapshotFile directory = persistedFiles.stream()
                .filter(file -> file.getFilePath().equals("src"))
                .findFirst()
                .orElseThrow();

        assertEquals("tree", directory.getFileType());
        assertEquals(FileCategory.OTHER, directory.getFileCategory());
        assertNull(directory.getScannedContent());
        assertNull(directory.getContentHash());
        assertNull(directory.getSecretsRedacted());
    }

    @Test
    void rejectsInvalidAuthorTimestampBeforeStartingTransaction() {
        RepositoryAnalysisService.AnalysisResult analysis =
                analysisResultWithCommitDate("not-a-timestamp");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.persist(connection, analysis));

        verify(transactionManager, never()).getTransaction(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void reloadsExistingSnapshotAfterDuplicateInsertRollback() {
        RepositorySnapshot existing = new RepositorySnapshot();
        when(snapshotRepository.findByRepositoryConnectionIdAndCommitSha(
                CONNECTION_ID,
                "commit-sha"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(snapshotRepository.save(any()))
                .thenThrow(constraintViolation(
                        "uq_repository_snapshot_commit"));

        RepositorySnapshot result = service.persist(
                connection,
                analysisResult());

        assertSame(existing, result);
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void propagatesAtomicFailureWhenDuplicateCannotBeReloaded() {
        when(snapshotRepository.findByRepositoryConnectionIdAndCommitSha(
                CONNECTION_ID,
                "commit-sha"))
                .thenReturn(Optional.empty());
        when(snapshotRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "database failure"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.persist(connection, analysisResult()));

        verify(transactionManager).rollback(transactionStatus);
        verify(snapshotFileRepository, never()).saveAll(any());
    }

    @Test
    void propagatesDifferentIntegrityViolationWithoutReloading() {
        RepositorySnapshot existing = new RepositorySnapshot();
        when(snapshotRepository.findByRepositoryConnectionIdAndCommitSha(
                CONNECTION_ID,
                "commit-sha"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        DataIntegrityViolationException failure = constraintViolation(
                "repository_snapshot_files_pkey");
        when(snapshotRepository.save(any())).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.persist(connection, analysisResult()));

        assertSame(failure, thrown);
        verify(snapshotRepository).findByRepositoryConnectionIdAndCommitSha(
                CONNECTION_ID,
                "commit-sha");
        verify(transactionManager).rollback(transactionStatus);
    }

    private DataIntegrityViolationException constraintViolation(
            String constraintName) {
        return new DataIntegrityViolationException(
                "integrity violation",
                new ConstraintViolationException(
                        "integrity violation",
                        null,
                        constraintName));
    }

    private void stubSaveOperations(List<RepositorySnapshotFile> persistedFiles) {
        when(snapshotRepository.save(any(RepositorySnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            for (Object value : (Iterable<?>) invocation.getArgument(0)) {
                persistedFiles.add((RepositorySnapshotFile) value);
            }
            return persistedFiles;
        }).when(snapshotFileRepository).saveAll(any(Iterable.class));
    }

    private ProjectRepositoryConnection connection() throws Exception {
        ProjectRepositoryConnection value =
                new ProjectRepositoryConnection();
        Field id = ProjectRepositoryConnection.class
                .getDeclaredField("repositoryConnectionId");
        id.setAccessible(true);
        id.set(value, CONNECTION_ID);
        return value;
    }

    private RepositoryAnalysisService.AnalysisResult analysisResult() {
        return analysisResultWithCommitDate("2026-01-01T00:00:00Z");
    }

    private RepositoryAnalysisService.AnalysisResult analysisResultWithCommitDate(
            String authorDate) {
        RepositoryAnalysisService.RepositoryMetadata metadata =
                new RepositoryAnalysisService.RepositoryMetadata(
                        "demo",
                        "octocat",
                        "Demo repository",
                        "https://github.com/acme/demo",
                        "main",
                        "Java",
                        "public",
                        false,
                        7,
                        2);
        RepositoryAnalysisService.CommitMetadata commit =
                new RepositoryAnalysisService.CommitMetadata(
                        "commit-sha",
                        "Initial commit",
                        authorDate);
        List<RepositoryAnalysisService.ClassifiedRepositoryFile> classified =
                List.of(
                        new RepositoryAnalysisService.ClassifiedRepositoryFile(
                                "src",
                                "tree",
                                0,
                                "directory-sha",
                                FileCategory.OTHER),
                        new RepositoryAnalysisService.ClassifiedRepositoryFile(
                                "src/App.java",
                                "blob",
                                12,
                                "file-sha",
                                FileCategory.SOURCE));
        List<ScannedFile> scanned = List.of(
                new ScannedFile(
                        "src/App.java",
                        FileCategory.SOURCE,
                        "class App {}",
                        true));

        return new RepositoryAnalysisService.AnalysisResult(
                metadata,
                commit,
                new com.proofix.backend.github.RepositoryTreeService
                        .RepositoryTree(List.of(), 0, false),
                classified,
                scanned,
                0,
                0,
                false,
                12);
    }
}

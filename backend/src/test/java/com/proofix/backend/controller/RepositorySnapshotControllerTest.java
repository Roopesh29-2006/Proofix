package com.proofix.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotHistoryResponse.SnapshotMetadata;
import com.proofix.backend.exception.GlobalExceptionHandler;
import com.proofix.backend.service.RepositoryAnalysisService.RepositoryMetadata;
import com.proofix.backend.service.RepositorySnapshotHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RepositorySnapshotControllerTest {

    private static final UUID SNAPSHOT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RepositorySnapshotHistoryService snapshotService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RepositorySnapshotController(
                        snapshotService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsSnapshotMetadataWithoutSourceContents() throws Exception {
        when(snapshotService.getSnapshot(SNAPSHOT_ID, USER_ID))
                .thenReturn(new SnapshotMetadata(
                        SNAPSHOT_ID,
                        "commit-sha",
                        "main",
                        "Initial commit",
                        null,
                        new RepositoryMetadata(
                                "demo",
                                "acme",
                                null,
                                "https://github.com/acme/demo",
                                "main",
                                "Java",
                                "public",
                                false,
                                1,
                                2),
                        3,
                        100,
                        null));

        mockMvc.perform(get("/api/repository/snapshots/{id}", SNAPSHOT_ID)
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId", is(SNAPSHOT_ID.toString())))
                .andExpect(jsonPath("$.commitSha", is("commit-sha")))
                .andExpect(jsonPath("$.scannedContent").doesNotExist())
                .andExpect(jsonPath("$.snapshotFiles").doesNotExist());

        verify(snapshotService).getSnapshot(SNAPSHOT_ID, USER_ID);
    }
}

package com.proofix.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotHistoryResponse;
import com.proofix.backend.exception.GlobalExceptionHandler;
import com.proofix.backend.service.RepositoryAnalysisService.RepositoryMetadata;
import com.proofix.backend.service.RepositorySnapshotHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RepositorySnapshotHistoryControllerTest {

    private static final UUID CONNECTION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RepositorySnapshotHistoryService historyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new RepositorySnapshotHistoryController(
                                historyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsMetadataOnlyWithPagination() throws Exception {
        RepositorySnapshotHistoryResponse response =
                new RepositorySnapshotHistoryResponse(
                        List.of(new RepositorySnapshotHistoryResponse
                                .SnapshotMetadata(
                                UUID.randomUUID(),
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
                                null)),
                        0,
                        10,
                        1,
                        1);
        when(historyService.getHistory(
                org.mockito.ArgumentMatchers.eq(CONNECTION_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);

        mockMvc.perform(get(
                        "/api/repository/connections/{id}/snapshots",
                        CONNECTION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].commitSha",
                        is("commit-sha")))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].scannedContent")
                        .doesNotExist())
                .andExpect(jsonPath("$.content[0].snapshotFiles")
                        .doesNotExist());

        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);
        verify(historyService).getHistory(
                org.mockito.ArgumentMatchers.eq(CONNECTION_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                pageable.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                PageRequest.of(0, 10), pageable.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(
                pageable.getValue().getSort().isUnsorted());
    }

    @Test
    void rejectsInvalidPageSize() throws Exception {
        mockMvc.perform(get(
                        "/api/repository/connections/{id}/snapshots",
                        CONNECTION_ID)
                        .queryParam("size", "51")
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null)))
                .andExpect(status().isBadRequest());
    }
}

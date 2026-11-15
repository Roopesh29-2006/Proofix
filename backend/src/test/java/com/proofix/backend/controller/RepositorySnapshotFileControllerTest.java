package com.proofix.backend.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.proofix.backend.dto.RepositorySnapshotFileResponse;
import com.proofix.backend.exception.GlobalExceptionHandler;
import com.proofix.backend.github.RepositoryFileClassifier.FileCategory;
import com.proofix.backend.service.RepositorySnapshotFileRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RepositorySnapshotFileControllerTest {

    private static final UUID SNAPSHOT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RepositorySnapshotFileRetrievalService fileService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RepositorySnapshotFileController(
                        fileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsFilesWithDefaultOptions() throws Exception {
        when(fileService.getFiles(
                org.mockito.ArgumentMatchers.eq(SNAPSHOT_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new RepositorySnapshotFileResponse(
                        List.of(new RepositorySnapshotFileResponse.FileMetadata(
                                UUID.randomUUID(),
                                "src/App.java",
                                "github-sha",
                                "blob",
                                FileCategory.SOURCE,
                                10,
                                "content-hash",
                                true,
                                null)),
                        0,
                        10,
                        1,
                        1));

        mockMvc.perform(get("/api/repository/snapshots/{id}/files",
                        SNAPSHOT_ID)
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].filePath",
                        is("src/App.java")))
                .andExpect(jsonPath("$.content[0].scannedContent")
                        .doesNotExist())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)));

        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);
        verify(fileService).getFiles(
                org.mockito.ArgumentMatchers.eq(SNAPSHOT_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                pageable.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false));
        org.junit.jupiter.api.Assertions.assertEquals(
                PageRequest.of(0, 10), pageable.getValue());
    }

    @Test
    void passesCategoryAndContentFlag() throws Exception {
        when(fileService.getFiles(
                org.mockito.ArgumentMatchers.eq(SNAPSHOT_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(FileCategory.CONFIGURATION),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new RepositorySnapshotFileResponse(
                        List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/repository/snapshots/{id}/files",
                        SNAPSHOT_ID)
                        .queryParam("page", "1")
                        .queryParam("size", "20")
                        .queryParam("category", "CONFIGURATION")
                        .queryParam("includeContent", "true")
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null)))
                .andExpect(status().isOk());

        verify(fileService).getFiles(
                org.mockito.ArgumentMatchers.eq(SNAPSHOT_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(PageRequest.of(1, 20)),
                org.mockito.ArgumentMatchers.eq(FileCategory.CONFIGURATION),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void rejectsInvalidPaginationAndCategory() throws Exception {
        mockMvc.perform(get("/api/repository/snapshots/{id}/files",
                        SNAPSHOT_ID)
                        .queryParam("size", "51")
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/repository/snapshots/{id}/files",
                        SNAPSHOT_ID)
                        .queryParam("category", "INVALID")
                        .principal(new UsernamePasswordAuthenticationToken(
                                USER_ID.toString(), null)))
                .andExpect(status().isBadRequest());
    }
}

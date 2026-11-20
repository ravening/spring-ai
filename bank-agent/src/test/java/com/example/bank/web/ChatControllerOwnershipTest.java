package com.example.bank.web;

import java.nio.file.Path;
import java.util.List;

import com.example.bank.agent.RunRecord;
import com.example.bank.auth.AuthInterceptor;
import com.example.bank.service.BankChatService;
import com.example.bank.voice.VoiceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Run endpoints must be readable only by the run's owner once token auth is on
 * (the interceptor stores the verified userId as a request attribute; in demo mode
 * there is no attribute and the check is skipped).
 */
class ChatControllerOwnershipTest {

    private BankChatService chatService;
    private MockMvc mvc;

    @TempDir
    Path audioDir;

    @BeforeEach
    void setUp() {
        chatService = mock(BankChatService.class);
        VoiceProperties voice = new VoiceProperties(false, "", "", "", "", "", 10_485_760L, 4000,
                audioDir.toString());
        RunRecord.RunStatus aliceRun = new RunRecord.RunStatus("r1", "conv-1", "alice", "COMPLETED",
                "hi", "answer", null, null, null, List.of(), List.of());
        when(chatService.status("r1")).thenReturn(aliceRun);
        when(chatService.status("unknown")).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService, voice))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static RequestPostProcessor asUser(String userId) {
        return request -> {
            request.setAttribute(AuthInterceptor.USER_ID_ATTRIBUTE, userId);
            return request;
        };
    }

    @Test
    void demoModeHasNoVerifiedIdentityAndStaysOpen() throws Exception {
        mvc.perform(get("/api/runs/{runId}", "r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("r1"))
                .andExpect(jsonPath("$.userId").value("alice"));
    }

    @Test
    void ownerCanReadTheRunWhenAuthIsEnabled() throws Exception {
        mvc.perform(get("/api/runs/{runId}", "r1").with(asUser("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("answer"));
    }

    @Test
    void otherUserGets403WhenAuthIsEnabled() throws Exception {
        mvc.perform(get("/api/runs/{runId}", "r1").with(asUser("bob")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/runs/{runId}/audio", "r1").with(asUser("bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRunIs404EvenForTheOwner() throws Exception {
        mvc.perform(get("/api/runs/{runId}", "unknown").with(asUser("alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerWithoutAudioGets404() throws Exception {
        mvc.perform(get("/api/runs/{runId}/audio", "r1").with(asUser("alice")))
                .andExpect(status().isNotFound());
    }
}

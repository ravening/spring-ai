package com.example.bank.web;

import java.nio.charset.StandardCharsets;

import com.example.bank.service.BankChatService;
import com.example.bank.voice.VoiceProperties;

import org.junit.jupiter.api.Test;

import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoiceControllerTest {

    private final BankChatService chatService = mock(BankChatService.class);
    private final TranscriptionModel transcriptionModel = mock(TranscriptionModel.class);
    private final VoiceProperties props = new VoiceProperties(true, "key", "https://audio.example/v1",
            "whisper-1", "gpt-4o-mini-tts", "alloy", 1024, 4000, "data/audio");

    @SuppressWarnings("unchecked")
    private MockMvc mvcWith(TranscriptionModel model) {
        ObjectProvider<TranscriptionModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return MockMvcBuilders.standaloneSetup(new VoiceController(chatService, provider, props))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void missingTranscriptionModelReturns503() throws Exception {
        mvcWith(null).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm", "x".getBytes()))
                        .param("userId", "alice"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void oversizeUploadReturns400() throws Exception {
        byte[] big = new byte[(int) props.maxUploadBytes() + 1];
        mvcWith(transcriptionModel).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm", big))
                        .param("userId", "alice"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyTranscriptionReturns400() throws Exception {
        when(transcriptionModel.transcribe(any(Resource.class))).thenReturn("   ");
        mvcWith(transcriptionModel).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm",
                                "audio-bytes".getBytes(StandardCharsets.UTF_8)))
                        .param("userId", "alice"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void happyPathTranscribesAndStartsRun() throws Exception {
        when(transcriptionModel.transcribe(any(Resource.class))).thenReturn("transfer 10 EUR to bob");
        when(chatService.submit(eq("alice"), isNull(), eq("transfer 10 EUR to bob")))
                .thenReturn(new BankChatService.StartRunResponse("conv-1", "run-1"));
        mvcWith(transcriptionModel).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm",
                                "audio-bytes".getBytes(StandardCharsets.UTF_8)))
                        .param("userId", "alice"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.runId").value("run-1"));
    }
}

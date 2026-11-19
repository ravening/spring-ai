package com.example.bank.web;

import java.nio.charset.StandardCharsets;

import com.example.bank.service.BankChatService;
import com.example.bank.voice.VoiceProperties;

import org.junit.jupiter.api.Test;

import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
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
    private MockMvc mvcWith(TranscriptionModel stt, TextToSpeechModel tts) {
        ObjectProvider<TranscriptionModel> sttProvider = mock(ObjectProvider.class);
        when(sttProvider.getIfAvailable()).thenReturn(stt);
        ObjectProvider<TextToSpeechModel> ttsProvider = mock(ObjectProvider.class);
        when(ttsProvider.getIfAvailable()).thenReturn(tts);
        return MockMvcBuilders.standaloneSetup(new VoiceController(chatService, sttProvider, ttsProvider, props))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void statusReportsWhichVoiceModelsAreConfigured() throws Exception {
        mvcWith(transcriptionModel, null).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/voice/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stt").value(true))
                .andExpect(jsonPath("$.tts").value(false));
        mvcWith(null, null).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/voice/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stt").value(false))
                .andExpect(jsonPath("$.tts").value(false));
    }

    @Test
    void missingTranscriptionModelReturns503() throws Exception {
        mvcWith(null, null).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm", "x".getBytes()))
                        .param("userId", "alice"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void oversizeUploadReturns400() throws Exception {
        byte[] big = new byte[(int) props.maxUploadBytes() + 1];
        mvcWith(transcriptionModel, null).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm", big))
                        .param("userId", "alice"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyTranscriptionReturns400() throws Exception {
        when(transcriptionModel.transcribe(any(Resource.class))).thenReturn("   ");
        mvcWith(transcriptionModel, null).perform(multipart("/api/chat/voice")
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
        mvcWith(transcriptionModel, null).perform(multipart("/api/chat/voice")
                        .file(new MockMultipartFile("audio", "a.webm", "audio/webm",
                                "audio-bytes".getBytes(StandardCharsets.UTF_8)))
                        .param("userId", "alice"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.runId").value("run-1"));
    }
}

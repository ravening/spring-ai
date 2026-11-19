package com.example.bank.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.example.bank.service.BankChatService;
import com.example.bank.voice.VoiceProperties;

import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Voice input for chat: the browser records audio and posts it here; the audio is
 * transcribed with the configured STT model and the resulting text is handed to the
 * regular chat pipeline, so conversation memory, input validation and the async
 * agent run behave exactly like a typed message.
 */
@RestController
@RequestMapping("/api")
public class VoiceController {

    private final BankChatService chatService;
    private final ObjectProvider<TranscriptionModel> transcriptionModel;
    private final ObjectProvider<TextToSpeechModel> speechModel;
    private final VoiceProperties voice;

    public VoiceController(BankChatService chatService, ObjectProvider<TranscriptionModel> transcriptionModel,
            ObjectProvider<TextToSpeechModel> speechModel, VoiceProperties voice) {
        this.chatService = chatService;
        this.transcriptionModel = transcriptionModel;
        this.speechModel = speechModel;
        this.voice = voice;
    }

    /**
     * Lets the UI decide how to do voice: server STT/TTS when enabled, otherwise
     * fall back to the browser's built-in speech (SpeechRecognition/speechSynthesis).
     */
    @GetMapping("/voice/status")
    public Map<String, Boolean> voiceStatus() {
        return Map.of("stt", this.transcriptionModel.getIfAvailable() != null,
                "tts", this.speechModel.getIfAvailable() != null);
    }

    @PostMapping(value = "/chat/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BankChatService.StartRunResponse> voiceChat(
            @RequestParam("userId") String userId,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam("audio") MultipartFile audio) throws IOException {
        TranscriptionModel model = this.transcriptionModel.getIfAvailable();
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Voice input is disabled. Set BANK_VOICE_ENABLED=true and AUDIO_API_KEY.");
        }
        if (audio.isEmpty() || audio.getSize() > this.voice.maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "audio file is empty or larger than " + this.voice.maxUploadBytes() + " bytes");
        }
        Path temp = Files.createTempFile("bank-voice-", ".audio");
        try {
            audio.transferTo(temp);
            String text = model.transcribe(new FileSystemResource(temp.toFile()));
            if (!StringUtils.hasText(text)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Could not transcribe any speech from the audio");
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(this.chatService.submit(userId, conversationId, text));
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }
}

package com.example.bank.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank.voice")
public record VoiceProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String sttModel,
        String ttsModel,
        String ttsVoice,
        long maxUploadBytes,
        int maxTtsChars,
        String audioDir) {
}

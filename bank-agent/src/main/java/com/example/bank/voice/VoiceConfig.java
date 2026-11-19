package com.example.bank.voice;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;

import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

/**
 * Speech-to-text and text-to-speech models for voice chat. The chat model goes
 * through DeepRelay, which does not proxy the OpenAI audio endpoints, so these
 * beans use their own base URL and API key (AUDIO_BASE_URL / AUDIO_API_KEY) and
 * are only created when bank.voice.enabled=true. The stock OpenAI audio
 * auto-configurations are disabled via spring.ai.model.audio.speech/transcription: none.
 */
@Configuration
@ConditionalOnProperty(prefix = "bank.voice", name = "enabled", havingValue = "true")
public class VoiceConfig {

    @Bean
    public OpenAiAudioSpeechModel speechModel(VoiceProperties props) {
        return OpenAiAudioSpeechModel.builder()
                .openAiClient(openAiClient(props))
                .observationRegistry(ObservationRegistry.NOOP)
                .options(OpenAiAudioSpeechOptions.builder()
                        .model(props.ttsModel())
                        .voice(props.ttsVoice())
                        .responseFormat("mp3")
                        .build())
                .build();
    }

    @Bean
    public OpenAiAudioTranscriptionModel transcriptionModel(VoiceProperties props) {
        OpenAIClient client = openAiClient(props);
        return OpenAiAudioTranscriptionModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .observationRegistry(ObservationRegistry.NOOP)
                .options(OpenAiAudioTranscriptionOptions.builder()
                        .model(props.sttModel())
                        .build())
                .build();
    }

    private OpenAIClient openAiClient(VoiceProperties props) {
        Assert.hasText(props.apiKey(), "bank.voice.api-key (AUDIO_API_KEY) is required when bank.voice.enabled=true");
        ClientOptions options = ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .build();
        return new OpenAIClientImpl(options);
    }
}

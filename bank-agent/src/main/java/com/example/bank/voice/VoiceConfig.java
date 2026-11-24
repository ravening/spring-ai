package com.example.bank.voice;

import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
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
import org.springframework.util.StringUtils;

/**
 * Speech-to-text and text-to-speech models for voice chat. The audio provider is
 * independent of the chat provider because DeepRelay does not proxy the OpenAI
 * audio endpoints. The beans use their own base URL and API key
 * (bank.voice.* / AUDIO_* or AZURE_OPENAI_*). The stock OpenAI audio
 * auto-configurations are disabled via
 * spring.ai.model.audio.speech/transcription: none.
 *
 * <p>When {@code bank.voice.azure.enabled=true} the client is built with Azure
 * auth/path handling. {@code bank.voice.azure.mode=legacy} targets
 * {@code https://<resource>.openai.azure.com} with an api-version, which is the
 * safest path for gpt-4o-mini-tts / gpt-4o-mini-transcribe. {@code mode=unified}
 * targets the OpenAI-compatible {@code /openai/v1} endpoint instead.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "bank.voice", name = "enabled", havingValue = "true")
public class VoiceConfig {

    private static final String DEFAULT_AZURE_SERVICE_VERSION = "2025-03-01-preview";

    @Bean
    public OpenAiAudioSpeechModel speechModel(VoiceProperties props, VoiceAzureProperties azure) {
        return OpenAiAudioSpeechModel.builder()
                .openAiClient(openAiClient(props, azure))
                .observationRegistry(ObservationRegistry.NOOP)
                .options(OpenAiAudioSpeechOptions.builder()
                        .model(props.ttsModel())
                        .voice(props.ttsVoice())
                        .responseFormat("mp3")
                        .build())
                .build();
    }

    @Bean
    public OpenAiAudioTranscriptionModel transcriptionModel(VoiceProperties props, VoiceAzureProperties azure) {
        OpenAIClient client = openAiClient(props, azure);
        return OpenAiAudioTranscriptionModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .observationRegistry(ObservationRegistry.NOOP)
                .options(OpenAiAudioTranscriptionOptions.builder()
                        .model(props.sttModel())
                        .build())
                .build();
    }

    private OpenAIClient openAiClient(VoiceProperties props, VoiceAzureProperties azure) {
        Assert.hasText(props.apiKey(),
                "bank.voice.api-key (AUDIO_API_KEY / AZURE_OPENAI_API_KEY) is required when bank.voice.enabled=true");
        Assert.hasText(props.baseUrl(), "bank.voice.base-url is required when bank.voice.enabled=true");

        AzureUrlPathMode pathMode = azure.enabled() ? urlPathMode(azure) : null;
        ClientOptions.Builder options = ClientOptions.builder()
                .httpClient(SpringAiOpenAiHttpClient.builder().build())
                .baseUrl(normalizedBaseUrl(props.baseUrl(), pathMode));

        if (azure.enabled()) {
            options.credential(AzureApiKeyCredential.create(props.apiKey()));
            options.azureUrlPathMode(pathMode);
            if (pathMode == AzureUrlPathMode.LEGACY) {
                options.azureServiceVersion(AzureOpenAIServiceVersion.Companion
                        .fromString(serviceVersion(azure)));
            }
        }
        else {
            options.apiKey(props.apiKey());
        }
        return new OpenAIClientImpl(options.build());
    }

    private static AzureUrlPathMode urlPathMode(VoiceAzureProperties azure) {
        return "unified".equalsIgnoreCase(azure.mode()) ? AzureUrlPathMode.UNIFIED : AzureUrlPathMode.LEGACY;
    }

    private static String serviceVersion(VoiceAzureProperties azure) {
        return StringUtils.hasText(azure.serviceVersion()) ? azure.serviceVersion() : DEFAULT_AZURE_SERVICE_VERSION;
    }

    private static String normalizedBaseUrl(String baseUrl, AzureUrlPathMode pathMode) {
        String url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (pathMode == null) {
            return url;
        }
        String v1Suffix = "/openai/v1";
        boolean hasV1Suffix = url.endsWith(v1Suffix);
        if (pathMode == AzureUrlPathMode.LEGACY) {
            return hasV1Suffix ? url.substring(0, url.length() - v1Suffix.length()) : url;
        }
        return hasV1Suffix ? url : url + v1Suffix;
    }
}

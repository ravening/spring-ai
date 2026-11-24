package com.example.bank.voice;

import java.lang.reflect.Field;

import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the voice models can be built for plain OpenAI-compatible audio,
 * Azure audio in legacy deployment-path mode and Azure audio on the unified
 * /openai/v1 endpoint. No network call is made; the client options are inspected
 * to prove which auth/path mode was selected.
 */
class VoiceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VoiceConfig.class, TestConfiguration.class)
            .withPropertyValues(
                    "bank.voice.enabled=true",
                    "bank.voice.api-key=test-key",
                    "bank.voice.stt-model=whisper-1",
                    "bank.voice.tts-model=gpt-4o-mini-tts",
                    "bank.voice.tts-voice=alloy");

    @Test
    void buildsModelsForPlainOpenAiAudio() {
        runner.withPropertyValues(
                        "bank.voice.base-url=https://api.openai.com/v1",
                        "bank.voice.azure.enabled=false")
                .run(context -> {
                    ClientOptions options = assertModelsPresent(context);
                    assertThat(options.baseUrl()).isEqualTo("https://api.openai.com/v1");
                    assertThat(options.apiKey()).contains("test-key");
                    assertThat(options.credential()).isInstanceOf(BearerTokenCredential.class);
                });
    }

    @Test
    void buildsModelsForAzureLegacyAudioAndStripsTheV1Suffix() {
        runner.withPropertyValues(
                        "bank.voice.base-url=https://example.openai.azure.com/openai/v1",
                        "bank.voice.azure.enabled=true",
                        "bank.voice.azure.mode=legacy",
                        "bank.voice.azure.service-version=2025-03-01-preview")
                .run(context -> {
                    ClientOptions options = assertModelsPresent(context);
                    assertThat(options.baseUrl()).isEqualTo("https://example.openai.azure.com");
                    assertThat(options.azureUrlPathMode()).isEqualTo(AzureUrlPathMode.LEGACY);
                    assertThat(options.azureServiceVersion().value()).isEqualTo("2025-03-01-preview");
                    assertThat(options.credential()).isInstanceOf(AzureApiKeyCredential.class);
                });
    }

    @Test
    void buildsModelsForAzureUnifiedAudioAndAppendsTheV1Suffix() {
        runner.withPropertyValues(
                        "bank.voice.base-url=https://example.openai.azure.com",
                        "bank.voice.azure.enabled=true",
                        "bank.voice.azure.mode=unified")
                .run(context -> {
                    ClientOptions options = assertModelsPresent(context);
                    assertThat(options.baseUrl()).isEqualTo("https://example.openai.azure.com/openai/v1");
                    assertThat(options.azureUrlPathMode()).isEqualTo(AzureUrlPathMode.UNIFIED);
                    assertThat(options.credential()).isInstanceOf(AzureApiKeyCredential.class);
                });
    }

    private static ClientOptions assertModelsPresent(AssertableApplicationContext context) throws Exception {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(OpenAiAudioSpeechModel.class);
        assertThat(context).hasSingleBean(OpenAiAudioTranscriptionModel.class);
        OpenAiAudioSpeechModel speechModel = context.getBean(OpenAiAudioSpeechModel.class);

        Field clientField = OpenAiAudioSpeechModel.class.getDeclaredField("openAiClient");
        clientField.setAccessible(true);
        OpenAIClient client = (OpenAIClient) clientField.get(speechModel);

        Field optionsField = OpenAIClientImpl.class.getDeclaredField("clientOptions");
        optionsField.setAccessible(true);
        return (ClientOptions) optionsField.get(client);
    }

    @Configuration
    @EnableConfigurationProperties({ VoiceProperties.class, VoiceAzureProperties.class })
    static class TestConfiguration {
    }
}

package com.example.bank.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Azure-specific settings for the voice models. Kept separate from
 * {@link VoiceProperties} so the existing OpenAI audio configuration keeps working
 * unchanged when Azure voice is disabled.
 */
@ConfigurationProperties(prefix = "bank.voice.azure")
public record VoiceAzureProperties(
        boolean enabled,
        String mode,
        String serviceVersion) {
}

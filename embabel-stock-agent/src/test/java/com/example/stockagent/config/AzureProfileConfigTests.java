package com.example.stockagent.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Azure profile wiring for Embabel without requiring Postgres or a
 * live model. The application itself is covered by the build and the Spring AI
 * Azure profile tests in the chat/agent/bank modules.
 */
class AzureProfileConfigTests {

    @Test
    void azureProfileDefinesFoundryAndEmbabelCustomModel() throws IOException {
        Map<String, Object> properties = new HashMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("azure", new ClassPathResource("application-azure.yml"))) {
            if (source instanceof MapPropertySource mapSource) {
                properties.putAll(mapSource.getSource());
            }
        }

        assertThat(properties.get("spring.ai.openai.microsoft-foundry").toString()).isEqualTo("true");
        assertThat(properties.get("spring.ai.openai.chat.model").toString())
                .isEqualTo("${AZURE_CHAT_DEPLOYMENT:gpt-4.1}");
        assertThat(properties.get("embabel.models.default-llm").toString())
                .isEqualTo("${AZURE_CHAT_DEPLOYMENT:gpt-4.1}");
        assertThat(properties.get("embabel.agent.platform.models.openai.custom.base-url").toString())
                .isEqualTo("${AZURE_OPENAI_ENDPOINT}");
        assertThat(properties.get("embabel.agent.platform.models.openai.custom.models").toString())
                .isEqualTo("${AZURE_CHAT_DEPLOYMENT:gpt-4.1}");
    }
}

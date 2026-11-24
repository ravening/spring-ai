package com.example.bank;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the bank application with the Azure profile and a dummy key. This
 * verifies the Microsoft Foundry OpenAI properties, the local RAG setup and the
 * new bank.voice.azure property binding. Voice and MCP are disabled, and no
 * model call is made.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=azure",
        "spring.ai.openai.base-url=https://example.openai.azure.com/openai/v1",
        "spring.ai.openai.api-key=test-key",
        "spring.datasource.url=jdbc:h2:mem:bank-azure-test;DB_CLOSE_DELAY=-1",
        "bank.mcp.enabled=false",
        "bank.jobs.enabled=false",
        "bank.voice.enabled=false",
        "bank.rag.vector-store-file=target/test-bank-vector-store-azure.json"
})
class AzureProfileTests {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithAzureChatAndVoiceProperties() {
        assertThat(environment.getProperty("spring.ai.openai.microsoft-foundry", Boolean.class)).isTrue();
        assertThat(environment.getProperty("bank.voice.azure.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("bank.voice.azure.mode")).isEqualTo("legacy");
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatOptions options = ((OpenAiChatModel) chatModel).getOptions();
        assertThat(options.getModel()).isEqualTo("gpt-4.1");
    }
}

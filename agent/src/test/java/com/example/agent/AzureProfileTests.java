package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the agent application with the Azure profile and a dummy key. This
 * verifies the Microsoft Foundry OpenAI properties and that the MCP client can
 * be disabled without affecting startup. No model call is made.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=azure",
        "spring.ai.openai.base-url=https://example.openai.azure.com/openai/v1",
        "spring.ai.openai.api-key=test-key",
        "spring.datasource.url=jdbc:h2:mem:agent-azure-test;DB_CLOSE_DELAY=-1",
        "agent.mcp.enabled=false",
        "agent.rag.enabled=false",
        "agent.rag.vector-store-file=target/test-agent-vector-store-azure.json"
})
class AzureProfileTests {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithAzureChatOptions() {
        assertThat(environment.getProperty("spring.ai.openai.microsoft-foundry", Boolean.class)).isTrue();
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatOptions options = ((OpenAiChatModel) chatModel).getOptions();
        assertThat(options.getModel()).isEqualTo("gpt-4.1");
    }
}

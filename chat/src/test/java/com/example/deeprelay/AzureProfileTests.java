package com.example.deeprelay;

import java.lang.reflect.Field;

import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application with the Azure profile and a dummy key. This verifies
 * that the Microsoft Foundry OpenAI properties are accepted by Spring AI and
 * that the local RAG beans still start. No model call is made.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=azure",
        "spring.ai.openai.base-url=https://example.openai.azure.com/openai/v1",
        "spring.ai.openai.api-key=test-key",
        "spring.datasource.url=jdbc:h2:mem:deeprelay-azure-test;DB_CLOSE_DELAY=-1",
        "rag.vector-store-file=target/test-vector-store-azure.json",
        "rag.ingest-classpath-docs=false"
})
class AzureProfileTests {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithAzureChatOptions() throws Exception {
        assertThat(environment.getProperty("spring.ai.openai.microsoft-foundry", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.ai.openai.chat.model")).isEqualTo("gpt-4.1");
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatOptions options = ((OpenAiChatModel) chatModel).getOptions();
        assertThat(options.getModel()).isEqualTo("gpt-4.1");

        ClientOptions clientOptions = clientOptions((OpenAiChatModel) chatModel);
        assertThat(clientOptions.baseUrl()).isEqualTo("https://example.openai.azure.com/openai/v1");
        assertThat(clientOptions.azureUrlPathMode()).isEqualTo(AzureUrlPathMode.UNIFIED);
        assertThat(clientOptions.credential()).isInstanceOf(AzureApiKeyCredential.class);
    }

    private static ClientOptions clientOptions(OpenAiChatModel model) throws Exception {
        Field clientField = OpenAiChatModel.class.getDeclaredField("openAiClient");
        clientField.setAccessible(true);
        OpenAIClient client = (OpenAIClient) clientField.get(model);

        Field optionsField = OpenAIClientImpl.class.getDeclaredField("clientOptions");
        optionsField.setAccessible(true);
        return (ClientOptions) optionsField.get(client);
    }
}

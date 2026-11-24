package com.example.deeprelay;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.datasource.url=jdbc:h2:mem:deeprelay-test;DB_CLOSE_DELAY=-1",
        "rag.vector-store-file=target/test-vector-store.json",
        "rag.ingest-classpath-docs=false"
})
class DeepRelayApplicationTests {

    @Autowired
    private ChatModel chatModel;

    @Test
    void contextLoadsWithDeepRelayDefaults() {
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatOptions options = ((OpenAiChatModel) chatModel).getOptions();
        assertThat(options.getModel()).isEqualTo("deeprelay/deepseek-v4.1-flash");
    }
}

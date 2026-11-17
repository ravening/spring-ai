package com.example.deeprelay.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
            @Value("${chat.memory.max-messages:80}") int maxMessages) {
        return new WindowedRepositoryChatMemory(chatMemoryRepository, maxMessages);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        return chatClientBuilder
                .defaultSystem("""
                        You are a helpful assistant.
                        Format answers in Markdown when it improves readability: use short paragraphs,
                        bullet or numbered lists, bold for key terms, tables for comparisons, and
                        fenced code blocks with a language tag for code.

                        """ + PromptSafety.dataInstruction())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}

package com.example.agent.chat;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
            @Value("${agent.chat.memory.max-messages:40}") int maxMessages) {
        return new WindowedRepositoryChatMemory(chatMemoryRepository, maxMessages);
    }
}

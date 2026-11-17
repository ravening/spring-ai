package com.example.agent.config;

import com.example.agent.core.PromptSafety;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    ChatClient assistantChatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        return chatClientBuilder.clone()
                .defaultSystem("""
                        You are a capable AI agent with access to local tools and remote tools
                        exposed through a Model Context Protocol (MCP) server.

                        Use a tool whenever the answer depends on calculations, current time,
                        support tickets, weather, inventory, currency exchange or shipping.
                        Never invent tool output. If a tool fails, explain the failure and
                        continue with the information you have.

                        Use the conversation history to understand follow-up questions and
                        pronouns that refer to earlier messages. If the user refers to
                        something discussed earlier, resolve it from the conversation history
                        instead of asking them to repeat it.

                        Format answers in Markdown: short paragraphs, bullet lists where useful,
                        and fenced code blocks with a language tag for code.

                        """ + PromptSafety.dataInstruction())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}

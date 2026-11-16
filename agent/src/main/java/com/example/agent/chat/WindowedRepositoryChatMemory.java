package com.example.agent.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;

/**
 * Persists the full conversation to the repository but only returns a window of
 * the most recent messages to the model.
 */
public final class WindowedRepositoryChatMemory implements ChatMemory {

    private final ChatMemoryRepository repository;

    private final int maxMessages;

    public WindowedRepositoryChatMemory(ChatMemoryRepository repository, int maxMessages) {
        Assert.notNull(repository, "repository cannot be null");
        Assert.isTrue(maxMessages > 0, "maxMessages must be greater than 0");
        this.repository = repository;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");
        if (messages.isEmpty()) {
            return;
        }

        List<Message> history = this.repository.findByConversationId(conversationId);
        List<Message> combined = new ArrayList<>(history.size() + messages.size());
        combined.addAll(history);
        combined.addAll(messages);
        this.repository.saveAll(conversationId, combined);
    }

    @Override
    public List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        return window(this.repository.findByConversationId(conversationId));
    }

    @Override
    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        this.repository.deleteByConversationId(conversationId);
    }

    private List<Message> window(List<Message> messages) {
        if (messages.size() <= this.maxMessages) {
            return messages;
        }

        List<Integer> nonSystemIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof SystemMessage)) {
                nonSystemIndices.add(i);
            }
        }

        int cutIndex = messages.size() - this.maxMessages;
        while (cutIndex < nonSystemIndices.size()
                && messages.get(nonSystemIndices.get(cutIndex)).getMessageType() != MessageType.USER) {
            cutIndex++;
        }
        cutIndex = Math.min(cutIndex, nonSystemIndices.size());

        Set<Integer> removeIndices = new HashSet<>(nonSystemIndices.subList(0, cutIndex));
        List<Message> windowed = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!removeIndices.contains(i)) {
                windowed.add(messages.get(i));
            }
        }
        return windowed;
    }
}

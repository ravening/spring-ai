package com.example.agent.chat;

import java.util.List;

import com.example.agent.core.ToolCallRecord;

public record ChatResponse(String conversationId, String answer, List<ToolCallRecord> toolCalls) {
}

package com.example.stockagent.domain;

public enum DocumentScope {
    GLOBAL,
    CHAT;

    public String scopeValue(String conversationId) {
        return this == GLOBAL ? "GLOBAL" : "CHAT:" + conversationId;
    }
}

package com.example.stockagent.service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which conversations have an agent process running, across tabs/reloads. */
@Service
public class StreamStatusService {

    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public void start(String conversationId) {
        if (conversationId != null) active.add(conversationId);
    }

    public void finish(String conversationId) {
        if (conversationId != null) active.remove(conversationId);
    }

    public boolean isActive(String conversationId) {
        return conversationId != null && active.contains(conversationId);
    }
}

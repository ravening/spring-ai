package com.example.bank.agent;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Tracks assistant runs. At most one run may be active per conversation, and the
 * active run id is exposed so the UI can show a "still working" indicator even
 * after switching chats or reloading the page.
 */
@Service
public class RunRegistry {

    private final ConcurrentHashMap<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeByConversation = new ConcurrentHashMap<>();

    public RunRecord start(String conversationId, String userId, String message) {
        RunRecord record = new RunRecord(conversationId, userId, message);
        runs.put(record.runId(), record);
        activeByConversation.put(conversationId, record.runId());
        return record;
    }

    public void complete(String runId, String answer) {
        RunRecord record = runs.get(runId);
        if (record != null) {
            record.complete(answer);
            activeByConversation.remove(record.conversationId(), runId);
        }
    }

    public void fail(String runId, String error) {
        RunRecord record = runs.get(runId);
        if (record != null) {
            record.fail(error);
            activeByConversation.remove(record.conversationId(), runId);
        }
    }

    public RunRecord get(String runId) {
        return runs.get(runId);
    }

    public String activeRunId(String conversationId) {
        return activeByConversation.get(conversationId);
    }

    public boolean isActive(String conversationId) {
        return activeByConversation.containsKey(conversationId);
    }
}

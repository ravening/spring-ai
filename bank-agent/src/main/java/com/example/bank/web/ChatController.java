package com.example.bank.web;

import com.example.bank.agent.RunRecord;
import com.example.bank.service.BankChatService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final BankChatService chatService;

    public ChatController(BankChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Starts an asynchronous agent run for a message. Returns immediately with the
     * conversation id and run id; poll {@link #runStatus(String)} for progress,
     * trace events, tool calls and the final answer.
     */
    @PostMapping("/chat")
    public ResponseEntity<BankChatService.StartRunResponse> chat(@RequestBody ChatRequest request) {
        BankChatService.StartRunResponse started = chatService.submit(
                request.userId(), request.conversationId(), request.message());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    }

    @GetMapping("/runs/{runId}")
    public RunRecord.RunStatus runStatus(@PathVariable String runId) {
        return chatService.status(runId);
    }

    public record ChatRequest(String userId, String conversationId, String message) {
    }
}

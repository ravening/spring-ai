package com.example.bank.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.bank.agent.RunRecord;
import com.example.bank.service.BankChatService;
import com.example.bank.voice.VoiceProperties;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final BankChatService chatService;
    private final VoiceProperties voice;

    public ChatController(BankChatService chatService, VoiceProperties voice) {
        this.chatService = chatService;
        this.voice = voice;
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

    /**
     * Spoken version of a completed run's answer (synthesized after the run finished).
     * 404 when voice is disabled or synthesis did not produce audio for this run.
     */
    @GetMapping("/runs/{runId}/audio")
    public ResponseEntity<Resource> runAudio(@PathVariable String runId) throws IOException {
        if (!runId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid runId");
        }
        Path file = Path.of(this.voice.audioDir()).resolve(runId + ".mp3");
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio for this run");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }

    public record ChatRequest(String userId, String conversationId, String message) {
    }
}

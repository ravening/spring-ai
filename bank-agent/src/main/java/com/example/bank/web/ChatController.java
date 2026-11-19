package com.example.bank.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.bank.auth.AuthSupport;
import com.example.bank.agent.RunRecord;
import jakarta.servlet.http.HttpServletRequest;
import com.example.bank.service.BankChatService;
import com.example.bank.voice.VoiceProperties;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;

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
    public ResponseEntity<BankChatService.StartRunResponse> chat(HttpServletRequest http,
            @RequestBody ChatRequest request) {
        String userId = AuthSupport.currentUserId(http, request.userId());
        BankChatService.StartRunResponse started = chatService.submit(
                userId, request.conversationId(), request.message());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    }

    @GetMapping("/runs/{runId}")
    public RunRecord.RunStatus runStatus(@PathVariable String runId) {
        return chatService.status(runId);
    }

    /**
     * Server-sent event stream of the answer as the writer produces it. Events:
     * {@code draft} (a new/revised draft starts), {@code delta} (JSON {@code {draft, delta}}),
     * then {@code done}. Falls back to plain polling when the browser has no EventSource.
     */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamPayload>> runStream(@PathVariable String runId) {
        return this.chatService.answerStream(runId)
                .map(ev -> ServerSentEvent.<StreamPayload>builder()
                        .event(ev.newDraft() ? "draft" : "delta")
                        .data(new StreamPayload(ev.newDraft(), ev.delta()))
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<StreamPayload>builder()
                        .event("done")
                        .data(new StreamPayload(false, ""))
                        .build()));
    }

    public record StreamPayload(boolean draft, String delta) {
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

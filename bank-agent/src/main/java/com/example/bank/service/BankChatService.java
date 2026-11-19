package com.example.bank.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.bank.agent.BankOrchestrator;
import com.example.bank.agent.RunRecord;
import com.example.bank.agent.RunRegistry;
import com.example.bank.chat.ConversationService;
import com.example.bank.core.AgentTrace;
import com.example.bank.core.ConversationId;
import com.example.bank.core.PromptSafety;
import com.example.bank.users.BankUser;
import com.example.bank.users.UserDirectory;
import com.example.bank.voice.VoiceProperties;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;

/**
 * Entry point for chat messages. Runs are asynchronous: the user message is
 * persisted immediately and the agent team runs on a background executor, so the
 * UI can keep working (and show a "still working" badge) while the answer is
 * being produced.
 */
@Service
public class BankChatService {

    private static final Logger log = LoggerFactory.getLogger(BankChatService.class);

    private final UserDirectory users;
    private final ConversationService conversations;
    private final BankOrchestrator orchestrator;
    private final RunRegistry runRegistry;
    private final ObjectProvider<TextToSpeechModel> speechModel;
    private final VoiceProperties voice;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public BankChatService(UserDirectory users, ConversationService conversations,
            BankOrchestrator orchestrator, RunRegistry runRegistry,
            ObjectProvider<TextToSpeechModel> speechModel, VoiceProperties voice) {
        this.users = users;
        this.conversations = conversations;
        this.orchestrator = orchestrator;
        this.runRegistry = runRegistry;
        this.speechModel = speechModel;
        this.voice = voice;
    }

    public record StartRunResponse(String conversationId, String runId) {
    }

    // Synchronized so the isActive check and runRegistry.start are atomic per JVM:
    // two rapid sends on the same conversation cannot start two overlapping runs.
    public synchronized StartRunResponse submit(String userId, String conversationId, String message) {
        BankUser user = users.resolve(userId);
        String cleanMessage = PromptSafety.validatedMessage(message);

        String cid = ConversationId.resolve(conversationId);
        boolean created = false;
        if (!conversations.exists(cid)) {
            conversations.create(user.id(), cid);
            created = true;
        }
        conversations.ensureOwnership(cid, user.id());

        if (runRegistry.isActive(cid)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This conversation is still processing the previous question. Please wait for it to finish.");
        }

        conversations.addUserMessage(cid, cleanMessage);
        if (created) {
            conversations.titleFromFirstMessage(cid, cleanMessage);
        }

        RunRecord run = runRegistry.start(cid, user.id(), cleanMessage);
        executor.submit(() -> execute(run, cleanMessage));
        return new StartRunResponse(cid, run.runId());
    }

    public RunRecord.RunStatus status(String runId) {
        RunRecord run = runRegistry.get(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        return run.snapshot();
    }

    /** Live answer-token stream for a run (SSE); 404 when the run does not exist. */
    public Flux<RunRecord.AnswerEvent> answerStream(String runId) {
        RunRecord run = runRegistry.get(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        }
        return run.answerEvents();
    }

    private void execute(RunRecord run, String message) {
        AgentTrace trace = run.trace();
        try {
            String answer = orchestrator.run(run.userId(), run.conversationId(), message, trace,
                    run::emitAnswerEvent);
            conversations.addAssistantMessage(run.conversationId(), answer);
            synthesizeSpeech(run.runId(), answer);
            runRegistry.complete(run.runId(), answer);
        }
        catch (Exception ex) {
            log.error("Banking run {} failed", run.runId(), ex);
            trace.event("run_failed", "orchestrator", "Run failed: " + ex.getMessage(), null, null);
            runRegistry.fail(run.runId(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /**
     * Best-effort text-to-speech for the final answer, stored next to the run so the
     * UI can play it via /api/runs/{runId}/audio. The text answer is always
     * delivered, even if voice is disabled or synthesis fails.
     */
    private void synthesizeSpeech(String runId, String answer) {
        TextToSpeechModel model = this.speechModel.getIfAvailable();
        if (model == null || !StringUtils.hasText(answer) || answer.length() > this.voice.maxTtsChars()) {
            return;
        }
        try {
            byte[] audio = model.call(answer);
            Path dir = Path.of(this.voice.audioDir());
            Files.createDirectories(dir);
            Files.write(dir.resolve(runId + ".mp3"), audio);
        }
        catch (Exception ex) {
            log.warn("Speech synthesis failed for run {}; the text answer is unaffected", runId, ex);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

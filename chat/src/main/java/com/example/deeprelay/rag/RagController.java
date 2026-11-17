package com.example.deeprelay.rag;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.deeprelay.chat.PromptSafety;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ChatClient chatClient;
    private final ConversationAwareRagAdvisor ragAdvisor;
    private final RagIngestionService ingestionService;
    private final RagRetrievalService retrievalService;
    private final RagProperties properties;

    public RagController(ChatClient chatClient, ConversationAwareRagAdvisor ragAdvisor,
            RagIngestionService ingestionService, RagRetrievalService retrievalService, RagProperties properties) {
        this.chatClient = chatClient;
        this.ragAdvisor = ragAdvisor;
        this.ingestionService = ingestionService;
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RagIngestionService.IngestionResult upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        String source = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "upload";
        return this.ingestionService.ingest(file.getResource(), source);
    }

    @PostMapping("/reindex")
    public RagIngestionService.ReindexResult reindex() {
        return this.ingestionService.reindexClasspathDocuments();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String message = StringUtils.hasText(ex.getReason()) ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }

    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam String query,
            @RequestParam(name = "conversationId", required = false) String conversationId,
            @RequestParam(name = "topK", required = false) Integer topK) {
        String safeQuery = PromptSafety.validatedMessage(query);
        int results = topK != null ? Math.max(1, Math.min(topK, 20)) : this.properties.topK();
        return this.retrievalService.retrieve(safeQuery, conversationId, results).stream()
                .map(SearchResult::from)
                .toList();
    }

    @GetMapping("/chat")
    public ChatResponse chat(@RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId) {
        String response = this.chatClient.prompt()
                .user(PromptSafety.validatedMessage(message))
                .advisors(this.ragAdvisor)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return new ChatResponse(response);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message,
            @RequestParam(defaultValue = "default") String conversationId) {
        return this.chatClient.prompt()
                .user(PromptSafety.validatedMessage(message))
                .advisors(this.ragAdvisor)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    public record ChatResponse(String response) {
    }

    public record SearchResult(String id, String source, Double score, String content) {

        static SearchResult from(Document document) {
            return new SearchResult(document.getId(),
                    Objects.toString(document.getMetadata().get("source"), ""),
                    document.getScore(),
                    document.getText());
        }
    }
}

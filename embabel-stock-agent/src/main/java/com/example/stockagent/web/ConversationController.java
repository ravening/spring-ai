package com.example.stockagent.web;

import com.example.stockagent.domain.ChatTurn;
import com.example.stockagent.service.ConversationService;
import com.example.stockagent.service.StreamStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversations;
    private final StreamStatusService streamStatus;

    public ConversationController(ConversationService conversations, StreamStatusService streamStatus) {
        this.conversations = conversations;
        this.streamStatus = streamStatus;
    }

    @GetMapping
    public java.util.List<ConversationService.ConversationInfo> list() {
        return conversations.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationService.ConversationInfo create(@RequestBody(required = false) Map<String, String> body) {
        String id = UUID.randomUUID().toString();
        String title = body == null ? null : body.get("title");
        conversations.create(id, title);
        return new ConversationService.ConversationInfo(id,
                title == null || title.isBlank() ? "New analysis" : title,
                System.currentTimeMillis() / 1000, 0);
    }

    /** Whether an agent process is running for this chat (survives page reload / tabs). */
    @GetMapping("/{id}/active")
    public Map<String, Boolean> active(@PathVariable String id) {
        return Map.of("active", streamStatus.isActive(id));
    }

    /** Full message history of a conversation, restored when the user switches chats. */
    @GetMapping("/{id}/messages")
    public java.util.List<ChatTurn> messages(@PathVariable String id) {
        return conversations.history(id);
    }

    @PatchMapping("/{id}")
    public void rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        conversations.rename(id, body.get("title"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversations.delete(id);
    }
}

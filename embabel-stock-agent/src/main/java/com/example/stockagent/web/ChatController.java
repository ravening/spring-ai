package com.example.stockagent.web;

import com.example.stockagent.domain.AnalysisEvent;
import com.example.stockagent.service.AnalysisService;
import com.example.stockagent.service.ChatService;
import com.example.stockagent.service.ConversationService;
import com.example.stockagent.service.QuestionRouterService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final AnalysisService analysisService;
    private final QuestionRouterService questionRouter;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService,
                          AnalysisService analysisService,
                          QuestionRouterService questionRouter,
                          ConversationService conversationService) {
        this.chatService = chatService;
        this.analysisService = analysisService;
        this.questionRouter = questionRouter;
        this.conversationService = conversationService;
    }

    /** Token-by-token SSE. Routes by intent: targeted answer vs full multi-agent analysis. */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AnalysisEvent>> stream(@RequestBody Map<String, String> body) {
        String conversationId = body.getOrDefault("conversationId", "default");
        String message = body.getOrDefault("message", "");
        var route = questionRouter.route(message, conversationService.history(conversationId));
        Flux<AnalysisEvent> events;

        if (route.hasTicker() && "ANALYSIS".equals(route.intent())) {
            // General analysis -> full Embabel multi-agent workflow.
            events = analysisService.analyse(route.ticker(), message, conversationId);
        } else if (route.hasTicker()) {
            // Specific question -> answer it directly from fresh, targeted data.
            events = chatService.answerQuestion(conversationId, message, route.ticker(), route.intent());
        } else {
            events = chatService.chat(conversationId, message);
        }

        return events.map(event -> ServerSentEvent.<AnalysisEvent>builder()
                .event(event.type())
                .data(event)
                .build());
    }
}

package com.example.stockagent.web;

import com.example.stockagent.domain.AnalysisEvent;
import com.example.stockagent.service.AnalysisService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AnalysisEvent>> stream(@RequestBody Map<String, String> body) {
        String ticker = body.getOrDefault("ticker", "");
        String question = body.getOrDefault("question", "");
        String conversationId = body.getOrDefault("conversationId", "default");
        return analysisService.analyse(ticker, question, conversationId)
                .map(event -> ServerSentEvent.<AnalysisEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());
    }
}

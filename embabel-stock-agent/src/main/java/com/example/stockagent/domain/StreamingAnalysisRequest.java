package com.example.stockagent.domain;

import reactor.core.publisher.Sinks;

import java.time.Duration;

/** Blackboard input carrying both user context and the SSE sink for streaming. */
public record StreamingAnalysisRequest(
        StockAnalysisRequest base,
        ConversationContext context,
        Sinks.Many<AnalysisEvent> sink
) {
    /** Parallel research branches share this sink, so emission must be serialised. */
    public synchronized void emit(AnalysisEvent event) {
        sink.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(2)));
    }
}

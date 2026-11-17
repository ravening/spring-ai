package com.example.stockagent.service;

import com.example.stockagent.domain.AnalysisEvent;
import com.example.stockagent.domain.ConversationContext;
import com.example.stockagent.domain.StockAnalysisRequest;
import com.example.stockagent.domain.StockReport;
import com.example.stockagent.domain.StreamingAnalysisRequest;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Starts the Embabel agent process and exposes its SSE events as a Flux. */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final AgentPlatform agentPlatform;
    private final ConversationService conversationService;
    private final DocumentService documentService;
    private final StreamStatusService streamStatus;

    public AnalysisService(AgentPlatform agentPlatform,
                           ConversationService conversationService,
                           DocumentService documentService,
                           StreamStatusService streamStatus) {
        this.agentPlatform = agentPlatform;
        this.conversationService = conversationService;
        this.documentService = documentService;
        this.streamStatus = streamStatus;
    }

    public Flux<AnalysisEvent> analyse(String ticker, String question, String conversationId) {
        streamStatus.start(conversationId);
        Sinks.Many<AnalysisEvent> sink = Sinks.many().replay().all();
        ConversationContext context = new ConversationContext(
                conversationId,
                conversationService.history(conversationId),
                documentService.search(question == null ? ticker : question, conversationId)
        );
        StreamingAnalysisRequest request = new StreamingAnalysisRequest(
                new StockAnalysisRequest(ticker, conversationId, question),
                context,
                sink
        );

        CompletableFuture.runAsync(() -> {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            // Embabel resolves domain types with Class.forName(name, true, TCCL).
            Thread.currentThread().setContextClassLoader(StockReport.class.getClassLoader());
            try {
                Agent agent = agentPlatform.agents().stream()
                        .filter(candidate -> "stock-analysis-agent".equals(candidate.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Stock analysis agent is not deployed"));
                AgentProcess process = agentPlatform.createAgentProcessFrom(
                        agent, ProcessOptions.DEFAULT, request);
                AgentProcess completed = agentPlatform.start(process).get(300, TimeUnit.SECONDS);
                StockReport report = completed.resultOfType(StockReport.class);
                if (report == null) {
                    throw new IllegalStateException("Agent completed without a StockReport result");
                }
                String userText = question == null || question.isBlank() ? ticker : question;
                conversationService.saveTurn(conversationId, userText, report.markdown());
                sink.emitNext(AnalysisEvent.done(),
                        Sinks.EmitFailureHandler.busyLooping(java.time.Duration.ofSeconds(2)));
                sink.emitComplete(Sinks.EmitFailureHandler.busyLooping(java.time.Duration.ofSeconds(2)));
                log.info("Analysis complete for {} ({} markdown chars)", ticker, report.markdown().length());
            } catch (Exception e) {
                log.error("Analysis failed for {}", ticker, e);
                sink.emitNext(AnalysisEvent.error("analysis",
                        "Analysis failed: " + e.getMessage()), Sinks.EmitFailureHandler.busyLooping(java.time.Duration.ofSeconds(2)));
                sink.emitComplete(Sinks.EmitFailureHandler.busyLooping(java.time.Duration.ofSeconds(2)));
            } finally {
                streamStatus.finish(conversationId);
                Thread.currentThread().setContextClassLoader(previous);
            }
        });
        return sink.asFlux();
    }
}

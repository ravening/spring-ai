package com.example.agent.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceTests {

    @Test
    void activeAgentIsIsolatedPerThread() throws Exception {
        AgentTrace trace = new AgentTrace("chat-1");
        trace.activeAgent("main");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String workerAgent = pool.submit(() -> {
                trace.activeAgent("researcher");
                return trace.activeAgent();
            }).get();

            assertThat(workerAgent).isEqualTo("researcher");
            assertThat(trace.activeAgent()).isEqualTo("main");
        }
        finally {
            pool.shutdownNow();
        }
    }
}

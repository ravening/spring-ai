package com.example.agent.mcpclient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.example.agent.core.TracingToolCallback;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final McpClientProperties properties;
    private final Object lock = new Object();

    private McpSyncClient client;
    private List<ToolCallback> callbacks = List.of();
    private List<McpToolInfo> tools = List.of();
    private String state = "DISCONNECTED";
    private String error;
    private Instant lastAttempt = Instant.EPOCH;

    public McpToolRegistry(McpClientProperties properties) {
        this.properties = properties;
    }

    public List<ToolCallback> toolCallbacks() {
        ensureConnected();
        synchronized (lock) {
            return callbacks;
        }
    }

    public McpStatus status() {
        ensureConnected();
        synchronized (lock) {
            if (!properties.enabled()) {
                return new McpStatus("DISABLED", properties.endpointUrl(), List.of(), null);
            }
            return new McpStatus(state, properties.endpointUrl(), tools, error);
        }
    }

    public McpStatus reconnect() {
        synchronized (lock) {
            closeClient();
            state = "DISCONNECTED";
            error = null;
            lastAttempt = Instant.EPOCH;
        }
        ensureConnected();
        return status();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (properties.enabled()) {
            CompletableFuture.runAsync(this::ensureConnected);
        }
    }

    private void ensureConnected() {
        if (!properties.enabled()) {
            return;
        }
        synchronized (lock) {
            if (client != null && client.isInitialized()) {
                return;
            }
            Instant now = Instant.now();
            if (now.isBefore(lastAttempt.plus(properties.retryCooldown()))) {
                return;
            }
            lastAttempt = now;
            closeClient();
            state = "CONNECTING";
            try {
                var transport = HttpClientStreamableHttpTransport.builder(properties.url())
                        .endpoint(properties.endpoint())
                        .connectTimeout(properties.connectTimeout())
                        .build();
                var newClient = McpClient.sync(transport)
                        .clientInfo(new McpSchema.Implementation("agent-demo-client", "1.0.0"))
                        .requestTimeout(properties.requestTimeout())
                        .build();
                newClient.initialize();
                List<McpSchema.Tool> discovered = newClient.listTools().tools();
                List<ToolCallback> newCallbacks = McpToolUtils.getToolCallbacksFromSyncClients(newClient)
                        .stream()
                        .map(callback -> (ToolCallback) new TracingToolCallback(callback, "mcp"))
                        .toList();

                this.client = newClient;
                this.callbacks = List.copyOf(newCallbacks);
                this.tools = discovered.stream()
                        .map(tool -> new McpToolInfo(tool.name(), tool.description()))
                        .toList();
                this.state = "CONNECTED";
                this.error = null;
                log.info("Connected to MCP server {} with {} tools: {}", properties.endpointUrl(),
                        tools.size(), tools.stream().map(McpToolInfo::name).toList());
            }
            catch (Exception ex) {
                this.state = "DISCONNECTED";
                this.error = ex.getMessage();
                this.callbacks = List.of();
                this.tools = List.of();
                closeClient();
                log.warn("Could not connect to MCP server {}: {}", properties.endpointUrl(), ex.getMessage());
            }
        }
    }

    private void closeClient() {
        if (client != null) {
            try {
                client.closeGracefully();
            }
            catch (Exception ex) {
                log.debug("Error closing MCP client", ex);
            }
            client = null;
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            closeClient();
        }
    }
}

package com.example.bank.mcpclient;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank.mcp")
public record McpClientProperties(
        boolean enabled,
        String url,
        String endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration retryCooldown) {

    public McpClientProperties {
        if (url == null || url.isBlank()) {
            url = "http://localhost:8096";
        }
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "/mcp";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(3);
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(30);
        }
        if (retryCooldown == null) {
            retryCooldown = Duration.ofSeconds(10);
        }
    }

    public String endpointUrl() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) + endpoint : url + endpoint;
    }
}

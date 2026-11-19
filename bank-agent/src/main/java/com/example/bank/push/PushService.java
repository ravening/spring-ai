package com.example.bank.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends a push notification when an agent run completes. Registrations are kept in
 * memory for the PoC; a production deployment should persist them per user/device.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushProperties properties;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public record Registration(String token, String platform) {
    }

    public PushService(PushProperties properties) {
        this.properties = properties;
    }

    public void register(String userId, String token, String platform) {
        registrations.put(userId, new Registration(token, platform == null ? "unknown" : platform));
    }

    public void unregister(String userId) {
        registrations.remove(userId);
    }

    public Registration registration(String userId) {
        return registrations.get(userId);
    }

    public int registrationCount() {
        return registrations.size();
    }

    /** Best-effort: a push failure never affects the chat answer. */
    public void notifyRunCompleted(String userId, String runId, String conversationId, String title, String body) {
        if (!properties.enabled()) {
            return;
        }
        Registration registration = registrations.get(userId);
        if (registration == null || registration.token().isBlank()) {
            return;
        }
        String payload = "{\"to\":" + quote(registration.token())
                + ",\"title\":" + quote(title)
                + ",\"body\":" + quote(body)
                + ",\"sound\":\"default\""
                + ",\"data\":" + "{\"runId\":" + quote(runId)
                + ",\"conversationId\":" + quote(conversationId)
                + ",\"url\":\"/conversations/" + escape(conversationId) + "\"}}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Push delivery failed with HTTP {}", response.statusCode());
            }
        } catch (Exception ex) {
            log.warn("Push delivery failed: {}", ex.getMessage());
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }
}

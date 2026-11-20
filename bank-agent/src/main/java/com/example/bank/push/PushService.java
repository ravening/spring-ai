package com.example.bank.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Best-effort Expo push "wake-up" signal. The authoritative record lives in the
 * notifications table; push carries only ids + a deep link, never financial detail.
 * A push failure is logged and ignored — it never rolls back a transfer or job run.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushProperties properties;
    private final PushDeviceStore devices;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PushService(PushProperties properties, PushDeviceStore devices) {
        this.properties = properties;
        this.devices = devices;
    }

    public void register(String userId, String token, String platform) {
        devices.register(userId, token, platform, System.currentTimeMillis());
    }

    public void unregisterToken(String userId, String token) {
        devices.unregisterToken(userId, token);
    }

    public void unregister(String userId) {
        devices.unregisterAll(userId);
    }

    public long registrationCount() {
        return devices.count();
    }

    public long registrationCount(String userId) {
        return devices.countForUser(userId);
    }

    /**
     * Sends a minimal wake-up to every device of a user. Expo receipts are consumed
     * opportunistically: stale DeviceNotRegistered tokens are removed from the table.
     */
    public void sendWakeUp(String userId, long notificationId, String conversationId, String url) {
        if (!properties.enabled()) {
            return;
        }
        for (String token : devices.tokensFor(userId)) {
            String payload = "{\"to\":" + quote(token)
                    + ",\"title\":\"Bank Agent\""
                    + ",\"body\":\"You have a new notification\""
                    + ",\"sound\":\"default\""
                    + ",\"data\":{\"notificationId\":" + notificationId
                    + ",\"conversationId\":" + quote(conversationId)
                    + ",\"url\":" + quote(url) + "}}";
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url()))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body();
                if (response.statusCode() >= 300) {
                    log.warn("Push delivery failed with HTTP {}", response.statusCode());
                }
                if (body.contains("DeviceNotRegistered")) {
                    devices.removeToken(token);
                    log.info("Removed stale push device token for user {}", userId);
                }
            } catch (Exception ex) {
                log.warn("Push delivery failed: {}", ex.getMessage());
            }
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

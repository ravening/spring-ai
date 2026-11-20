package com.example.bank.push;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the push contract from the plan: push is only a wake-up (ids + deep link,
 * NEVER amounts/balances), it fans out to every registered device, stale tokens are
 * removed from Expo receipts, and a disabled config sends nothing.
 */
class PushServiceTest {

    private HttpServer server;
    private List<String> payloads;
    private PushDeviceStore devices;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        payloads = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            payloads.add(new String(body, StandardCharsets.UTF_8));
            String response = payloads.get(payloads.size() - 1).contains("STALE")
                    ? "{\"data\":[{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}"
                    : "{\"data\":[{\"status\":\"ok\"}]}";
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";

        String url = "jdbc:h2:mem:p_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        devices = new PushDeviceStore(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
        devices.createTable();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private PushService service(boolean enabled) {
        return new PushService(new PushProperties(enabled, baseUrl), devices);
    }

    @Test
    void payloadCarriesOnlyIdsAndDeepLinkAndFansOutToAllDevices() {
        devices.register("alice", "ExpoPushToken[A]", "ios", 1);
        devices.register("alice", "ExpoPushToken[B]", "android", 2);
        devices.register("bob", "ExpoPushToken[C]", "ios", 3);

        service(true).sendWakeUp("alice", 42, "conv-1", "/conversations/conv-1");

        assertEquals(2, payloads.size(), "one wake-up per device of the recipient");
        for (String payload : payloads) {
            assertTrue(payload.contains("\"notificationId\":42"));
            assertTrue(payload.contains("\"conversationId\":\"conv-1\""));
            assertTrue(payload.contains("\"url\":\"/conversations/conv-1\""));
            assertTrue(payload.contains("\"title\":\"Bank Agent\""));
            assertTrue(payload.contains("\"body\":\"You have a new notification\""));
            assertFalse(payload.contains("amount"), "no financial detail in push");
            assertFalse(payload.contains("balance"), "no financial detail in push");
            assertFalse(payload.contains("txn"), "no transaction id in push");
            assertFalse(payload.contains("IBAN"), "no account detail in push");
        }
        assertTrue(payloads.stream().anyMatch(p -> p.contains("ExpoPushToken[A]")));
        assertTrue(payloads.stream().anyMatch(p -> p.contains("ExpoPushToken[B]")));
        assertFalse(payloads.stream().anyMatch(p -> p.contains("ExpoPushToken[C]")));
    }

    @Test
    void deviceNotRegisteredReceiptRemovesStaleToken() {
        devices.register("alice", "STALE-TOKEN", "ios", 1);
        devices.register("alice", "GOOD-TOKEN", "ios", 2);
        service(true).sendWakeUp("alice", 7, "conv-1", "/conversations/conv-1");

        assertEquals(2, payloads.size());
        assertEquals(List.of("GOOD-TOKEN"), devices.tokensFor("alice"));
    }

    @Test
    void disabledPushSendsNothing() {
        devices.register("alice", "ExpoPushToken[A]", "ios", 1);
        service(false).sendWakeUp("alice", 1, "conv-1", "/conversations/conv-1");
        assertTrue(payloads.isEmpty());
        assertEquals(1, devices.tokensFor("alice").size());
    }
}

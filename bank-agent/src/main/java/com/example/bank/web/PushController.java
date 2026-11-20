package com.example.bank.web;

import java.util.Map;

import com.example.bank.auth.AuthSupport;
import com.example.bank.push.PushProperties;
import com.example.bank.push.PushService;
import com.example.bank.ratelimit.RateLimitProperties;
import com.example.bank.ratelimit.RateLimiter;
import com.example.bank.users.UserDirectory;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Device push-token registration used for "answer ready" notifications. */
@RestController
@RequestMapping("/api/notifications")
public class PushController {

    private final PushService push;
    private final PushProperties properties;
    private final UserDirectory users;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public PushController(PushService push, PushProperties properties, UserDirectory users,
            RateLimiter rateLimiter, RateLimitProperties rateLimitProperties) {
        this.push = push;
        this.properties = properties;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", properties.enabled(), "registrations", push.registrationCount());
    }

    @PostMapping("/register")
    public Map<String, Object> register(HttpServletRequest http, @RequestBody RegistrationRequest request) {
        String userId = AuthSupport.currentUserId(http, request.userId());
        users.resolve(userId);
        rateLimiter.check("registrations", userId, rateLimitProperties.registrationsPerMinute());
        if (request.token() == null || request.token().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "token is required");
        }
        push.register(userId, request.token(), request.platform());
        return Map.of("registered", true, "userId", userId);
    }

    /**
     * Removes a single device when a token is supplied; with no token this removes
     * every device of the user (used on logout).
     */
    @DeleteMapping("/register")
    public Map<String, Object> unregister(HttpServletRequest http,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String token) {
        String user = AuthSupport.currentUserId(http, userId);
        if (token != null && !token.isBlank()) {
            push.unregisterToken(user, token);
        } else {
            push.unregister(user);
        }
        return Map.of("registered", false);
    }

    public record RegistrationRequest(String userId, String token, String platform) {
    }
}

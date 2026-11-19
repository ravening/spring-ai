package com.example.bank.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lets the mobile client discover whether token auth is required and who it is. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties properties;

    public AuthController(AuthProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", properties.enabled(), "tokens", properties.configuredTokens());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request,
            @RequestParam(required = false) String userId) {
        return Map.of(
                "userId", AuthSupport.currentUserId(request, userId == null ? "" : userId),
                "authenticated", AuthSupport.authenticated(request));
    }
}

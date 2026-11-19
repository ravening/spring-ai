package com.example.bank.auth;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Demo-friendly token auth. Disabled by default (the web UI's customer picker keeps
 * working). When enabled with BANK_AUTH_ENABLED=true, a Bearer token from
 * BANK_AUTH_TOKENS (format: "alice:token1,bob:token2,carol:token3") is the ONLY accepted
 * identity; any userId supplied by the client is ignored by the controllers.
 */
@Component
public class AuthProperties {

    private final boolean enabled;
    private final Map<String, String> tokenToUser = new HashMap<>();

    public AuthProperties(
            @Value("${bank.auth.enabled:false}") boolean enabled,
            @Value("${bank.auth.tokens:}") String tokens) {
        this.enabled = enabled;
        for (String pair : tokens.split(",")) {
            int separator = pair.indexOf(':');
            if (separator > 0) {
                String user = pair.substring(0, separator).trim();
                String token = pair.substring(separator + 1).trim();
                if (!user.isEmpty() && !token.isEmpty()) {
                    tokenToUser.put(token, user);
                }
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String userIdForToken(String token) {
        return token == null ? null : tokenToUser.get(token.trim());
    }

    public int configuredTokens() {
        return tokenToUser.size();
    }
}

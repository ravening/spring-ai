package com.example.bank.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Validates Bearer tokens and exposes the verified userId as a request attribute. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "bank.authenticatedUserId";

    private final AuthProperties properties;

    public AuthInterceptor(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        if (!properties.enabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // let CORS preflight through
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Bearer token required");
            return false;
        }
        String userId = properties.userIdForToken(header.substring(7));
        if (userId == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid bearer token");
            return false;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }
}

package com.example.bank.auth;

import jakarta.servlet.http.HttpServletRequest;

/** Single place where controllers decide which userId a request acts as. */
public final class AuthSupport {

    private AuthSupport() {
    }

    public static String currentUserId(HttpServletRequest request, String supplied) {
        Object verified = request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return verified instanceof String userId ? userId : supplied;
    }

    public static boolean authenticated(HttpServletRequest request) {
        return request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE) != null;
    }
}

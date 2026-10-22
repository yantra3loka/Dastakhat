package com.dastakhat.common;

import jakarta.servlet.http.HttpServletRequest;

/** Extracts the client information that is stored in the audit trail. */
public final class RequestContext {

    private RequestContext() {
    }

    /** The IP address of the caller, honouring a single reverse proxy hop. */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first, 64);
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp.trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    /** The user agent of the caller. */
    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return truncate(request.getHeader("User-Agent"), 400);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}

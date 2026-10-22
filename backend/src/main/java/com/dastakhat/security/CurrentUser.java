package com.dastakhat.security;

import com.dastakhat.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Convenience access to the authenticated sender. */
@Component
public class CurrentUser {

    /** Returns the id of the authenticated sender or {@code null} when anonymous. */
    public UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SenderPrincipal principal)) {
            return null;
        }
        return principal.getId();
    }

    /** Returns the id of the authenticated sender or fails with 401. */
    public UUID requireId() {
        UUID id = idOrNull();
        if (id == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED.value(), "unauthorized", "Authentication required.");
        }
        return id;
    }
}

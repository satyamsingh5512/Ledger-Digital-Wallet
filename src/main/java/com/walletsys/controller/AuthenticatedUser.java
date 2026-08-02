package com.walletsys.controller;

import com.walletsys.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Extracts the authenticated user's id from the security context set by JwtAuthenticationFilter. */
final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getId();
        }
        throw new IllegalStateException("No authenticated user in security context");
    }
}

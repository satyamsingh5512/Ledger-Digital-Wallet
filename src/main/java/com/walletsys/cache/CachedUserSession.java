package com.walletsys.cache;

import java.io.Serializable;
import java.util.UUID;

/**
 * Minimal, cache-friendly projection of a user's session-relevant attributes. Kept as a
 * plain record (not the {@code UserPrincipal}/{@code UserDetails} object itself) so
 * Redis serialization doesn't need to round-trip Spring Security's
 * {@code GrantedAuthority} object graph — this is reconstructed into a
 * {@code UserPrincipal} on the read side by {@code CustomUserDetailsService}.
 */
public record CachedUserSession(
        UUID id,
        String email,
        String passwordHash,
        String role,
        boolean enabled
) implements Serializable {
}

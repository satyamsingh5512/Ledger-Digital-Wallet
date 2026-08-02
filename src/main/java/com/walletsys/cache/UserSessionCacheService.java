package com.walletsys.cache;

import java.util.Optional;
import java.util.UUID;

/**
 * Caches the authenticated user's session-relevant data (id, email, role, enabled flag)
 * in Redis, keyed by user id, to avoid a DB round trip on every authenticated request.
 *
 * <p>In this stateless-JWT architecture there is no server-side session object to
 * cache in the traditional sense (no {@code HttpSession}) — "user session caching"
 * here means caching the result of the user lookup that {@link
 * com.walletsys.security.CustomUserDetailsService} would otherwise perform against
 * Postgres on every single authenticated request (once per request, since
 * {@code JwtAuthenticationFilter} runs on every request to populate the security
 * context). This is the highest-frequency read in the entire system next to wallet
 * balance, so caching it materially reduces DB load at scale.</p>
 *
 * <p>Invalidated explicitly on logout-all and on any profile mutation that changes
 * fields baked into the cached session (email, role, status) — see
 * {@code RefreshTokenService.revokeAllForUser} callers and future profile-update flows.</p>
 */
public interface UserSessionCacheService {

    Optional<CachedUserSession> get(UUID userId);

    void put(CachedUserSession session);

    void evict(UUID userId);
}

package com.walletsys.security;

import com.walletsys.cache.UserSessionCacheService;
import com.walletsys.entity.RefreshToken;
import com.walletsys.entity.User;
import com.walletsys.exception.InvalidTokenException;
import com.walletsys.repository.RefreshTokenRepository;
import com.walletsys.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Issues, validates, and revokes opaque refresh tokens.
 *
 * <p>The raw token is returned to the client exactly once (at issuance) and never
 * persisted — only its SHA-256 hash is stored in {@code refresh_tokens}. This means a
 * database compromise alone cannot be used to impersonate users via their refresh
 * tokens, mirroring how we never store raw passwords.</p>
 *
 * <p><b>Rotation policy:</b> every successful {@code /auth/refresh} call revokes the
 * presented refresh token and issues a brand new one (rotation-on-use). This detects
 * token theft: if an attacker and the legitimate user both try to use the same
 * (stolen) refresh token, whichever one uses it second will find it already revoked
 * and be forced to re-authenticate — a strong signal to invalidate the whole session.</p>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final UserSessionCacheService userSessionCacheService;

    @Transactional
    public String issue(User user) {
        String rawToken = KeyGenerators.string().generateKey() + KeyGenerators.string().generateKey();
        String hash = sha256(rawToken);

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash)
                .expiresAt(Instant.now().plusMillis(jwtService.getRefreshTokenExpirationMs()))
                .revoked(false)
                .build();

        refreshTokenRepository.save(token);
        return rawToken;
    }

    @Transactional
    public RefreshToken validateAndConsume(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHashAndRevokedFalse(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or has been revoked"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token has expired");
        }

        token.setRevoked(true); // rotation-on-use
        refreshTokenRepository.save(token);
        return token;
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId);
        userSessionCacheService.evict(userId); // force re-fetch of role/status on next request
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

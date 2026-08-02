package com.walletsys.cache.impl;

import com.walletsys.cache.CachedUserSession;
import com.walletsys.cache.UserSessionCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionCacheServiceImpl implements UserSessionCacheService {

    private static final String KEY_PREFIX = "user:session:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Optional<CachedUserSession> get(UUID userId) {
        try {
            Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (cached instanceof CachedUserSession session) {
                return Optional.of(session);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis GET failed for user session {}, falling back to DB: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(CachedUserSession session) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + session.id(), session, TTL);
        } catch (Exception e) {
            log.warn("Redis SET failed for user session {}: {}", session.id(), e.getMessage());
        }
    }

    @Override
    public void evict(UUID userId) {
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Redis DEL failed for user session {}: {}", userId, e.getMessage());
        }
    }
}

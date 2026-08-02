package com.walletsys.cache.impl;

import com.walletsys.cache.WalletCacheService;
import com.walletsys.dto.response.WalletResponse;
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
public class WalletCacheServiceImpl implements WalletCacheService {

    private static final String KEY_PREFIX = "wallet:balance:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public Optional<WalletResponse> get(UUID walletId) {
        try {
            Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + walletId);
            if (cached instanceof WalletResponse walletResponse) {
                return Optional.of(walletResponse);
            }
            return Optional.empty();
        } catch (Exception e) {
            // Redis being unavailable must never fail a balance read — fall through to DB.
            log.warn("Redis GET failed for wallet {}, falling back to DB: {}", walletId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(WalletResponse wallet) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + wallet.getId(), wallet, TTL);
        } catch (Exception e) {
            log.warn("Redis SET failed for wallet {}: {}", wallet.getId(), e.getMessage());
        }
    }

    @Override
    public void evict(UUID walletId) {
        try {
            redisTemplate.delete(KEY_PREFIX + walletId);
        } catch (Exception e) {
            log.warn("Redis DEL failed for wallet {}: {}", walletId, e.getMessage());
        }
    }
}

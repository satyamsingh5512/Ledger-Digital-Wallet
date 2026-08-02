package com.walletsys.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Detects redelivery of a Kafka message the consumer has already successfully
 * processed, using Redis {@code SETNX} semantics (atomic "set if absent").
 *
 * <p>Redelivery is expected and normal in an at-least-once system: a consumer crash
 * after processing but before committing its offset, a rebalance, or a producer retry
 * after an ambiguous broker response can all cause the same event to be delivered more
 * than once. Since this consumer's side effect (sending a notification) is not itself
 * transactionally tied to Kafka offset commits, we use this short-lived "seen events"
 * cache to avoid sending duplicate notifications for the same {@code eventId} — the
 * dedup key that was set by the producer (see EventPublisher, which keys every Kafka
 * message by the event's own UUID).</p>
 *
 * <p>If Redis is briefly unavailable, we fail open (treat as "not seen") rather than
 * blocking notification delivery — an occasional duplicate notification is a much
 * smaller problem than a stalled consumer group.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDeduplicationService {

    private static final String KEY_PREFIX = "notif:seen-event:";
    private static final Duration TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    /** @return true if this is the first time we've seen this eventId (caller should process it) */
    public boolean markSeenIfAbsent(UUID eventId) {
        try {
            Boolean wasAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + eventId, Boolean.TRUE, TTL);
            return Boolean.TRUE.equals(wasAbsent);
        } catch (Exception e) {
            log.warn("Redis dedup check failed for event {}, processing anyway (fail-open): {}",
                    eventId, e.getMessage());
            return true;
        }
    }
}

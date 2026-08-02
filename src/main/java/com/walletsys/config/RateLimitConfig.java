package com.walletsys.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.serialization.Mapper;
import io.github.bucket4j.redis.jedis.Bucket4jJedis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Provides a Redis-backed {@link ProxyManager}, the bucket4j abstraction that persists
 * token-bucket state in Redis so rate limits are enforced consistently across every
 * instance in the fleet rather than per-process (a per-process in-memory bucket would
 * let a client get {@code N x instance-count} requests through simply by hitting
 * different instances behind the load balancer).
 *
 * <p>We use the Jedis integration (synchronous, connection-pooled) rather than Lettuce
 * (async) because the rate-limit check in {@code RateLimitFilter} is itself synchronous
 * (it must complete before the servlet filter chain proceeds) — there's no benefit to
 * an async Redis client here, and Jedis's simpler connection-pool model is easier to
 * reason about and tune for this access pattern.</p>
 *
 * <p>A dedicated {@link JedisPool} is used here (separate from the {@code RedisTemplate}
 * used elsewhere for caching) because bucket4j's Jedis integration operates directly on
 * {@code JedisPool}, not through Spring Data Redis's abstraction.</p>
 */
@Configuration
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Bean
    public JedisPool rateLimitJedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(4);

        if (StringUtils.hasText(redisPassword)) {
            return new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
        }
        return new JedisPool(poolConfig, redisHost, redisPort, 2000);
    }

    @Bean
    public ProxyManager<String> rateLimitProxyManager(JedisPool rateLimitJedisPool) {
        return Bucket4jJedis.casBasedBuilder(rateLimitJedisPool)
                .keyMapper(Mapper.STRING)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(30)))
                .build();
    }
}

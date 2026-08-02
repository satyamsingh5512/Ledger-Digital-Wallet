package com.walletsys.kafka.outbox;

import com.walletsys.config.OutboxProperties;
import com.walletsys.entity.OutboxEvent;
import com.walletsys.entity.enums.OutboxStatus;
import com.walletsys.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Status-update helpers for {@link OutboxPoller}, split into their own bean so the
 * {@code @Transactional(REQUIRES_NEW)} annotations are honored (self-invocation from
 * {@code OutboxPoller} would otherwise bypass the transactional proxy).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxStatusUpdater {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties outboxProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(OutboxEvent event) {
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        outboxEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedOrRetry(OutboxEvent event, String errorMessage) {
        int nextRetryCount = event.getRetryCount() + 1;
        event.setRetryCount(nextRetryCount);
        event.setErrorMessage(errorMessage);

        if (nextRetryCount >= outboxProperties.getMaxRetries()) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("Outbox event {} exhausted {} retries, marking FAILED", event.getId(), nextRetryCount);
        }

        outboxEventRepository.save(event);
    }
}

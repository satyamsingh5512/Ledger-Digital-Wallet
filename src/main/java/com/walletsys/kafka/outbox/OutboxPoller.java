package com.walletsys.kafka.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletsys.config.OutboxProperties;
import com.walletsys.entity.OutboxEvent;
import com.walletsys.entity.enums.OutboxStatus;
import com.walletsys.kafka.event.MoneyCreditedEvent;
import com.walletsys.kafka.event.MoneyDebitedEvent;
import com.walletsys.kafka.event.MoneyTransferredEvent;
import com.walletsys.kafka.event.RefundCompletedEvent;
import com.walletsys.kafka.event.WalletCreatedEvent;
import com.walletsys.kafka.producer.EventPublisher;
import com.walletsys.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls {@code outbox_events} for PENDING rows and publishes each to Kafka.
 *
 * <p><b>Why polling instead of, say, Debezium/CDC?</b> A CDC-based outbox (tailing the
 * Postgres WAL) removes polling latency and DB load entirely, and is the better choice
 * at very high event volume. We use a scheduled poller here because it requires no
 * extra infrastructure (Kafka Connect + Debezium), is trivial to reason about and test,
 * and the polling interval (500ms by default) is already well within acceptable
 * latency for wallet notifications. If event volume grows to the point where polling
 * every N ms starts to show up as meaningful DB load, migrating to CDC is a drop-in
 * replacement for this class — the outbox table schema doesn't need to change.</p>
 *
 * <p>Each event is published in its own small transaction (status update only) so a
 * failure on one event never blocks the rest of the batch.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.outbox.poller-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final OutboxProperties outboxProperties;
    private final OutboxStatusUpdater statusUpdater;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void pollAndPublish() {
        List<OutboxEvent> batch = outboxEventRepository.findPendingBatch(
                OutboxStatus.PENDING, PageRequest.of(0, outboxProperties.getBatchSize()));

        if (batch.isEmpty()) {
            return;
        }

        log.debug("Outbox poller picked up {} pending event(s)", batch.size());
        for (OutboxEvent event : batch) {
            processOne(event);
        }
    }

    private void processOne(OutboxEvent event) {
        try {
            Object payload = deserializePayload(event);
            eventPublisher.publish(event, payload).join();
            statusUpdater.markPublished(event);
        } catch (Exception e) {
            log.error("Failed to process outbox event {} ({}): {}", event.getId(), event.getEventType(), e.getMessage());
            statusUpdater.markFailedOrRetry(event, e.getMessage());
        }
    }

    private Object deserializePayload(OutboxEvent event) throws Exception {
        return switch (event.getEventType()) {
            case "WalletCreated" -> objectMapper.readValue(event.getPayload(), WalletCreatedEvent.class);
            case "MoneyTransferred" -> objectMapper.readValue(event.getPayload(), MoneyTransferredEvent.class);
            case "MoneyCredited" -> objectMapper.readValue(event.getPayload(), MoneyCreditedEvent.class);
            case "MoneyDebited" -> objectMapper.readValue(event.getPayload(), MoneyDebitedEvent.class);
            case "RefundCompleted" -> objectMapper.readValue(event.getPayload(), RefundCompletedEvent.class);
            default -> throw new IllegalStateException("Unknown outbox event type: " + event.getEventType());
        };
    }
}

package com.walletsys.kafka.consumer;

import com.walletsys.kafka.event.MoneyCreditedEvent;
import com.walletsys.kafka.event.MoneyDebitedEvent;
import com.walletsys.kafka.event.MoneyTransferredEvent;
import com.walletsys.kafka.event.RefundCompletedEvent;
import com.walletsys.kafka.event.WalletCreatedEvent;
import com.walletsys.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Notification service consumer: listens to every business event topic and dispatches a
 * (simulated) user notification.
 *
 * <p><b>Acknowledgment strategy:</b> {@code ack-mode: MANUAL_IMMEDIATE} (set in
 * application.yml / KafkaListenerConfig) means Spring Kafka does NOT auto-commit offsets
 * after each poll — each listener method explicitly calls {@link Acknowledgment#acknowledge()}
 * only after successfully processing the record. If processing throws, the offset is
 * never acknowledged, and combined with {@code isolation.level: read_committed} and the
 * container's {@link org.springframework.kafka.listener.DefaultErrorHandler} (retry then
 * dead-letter), the message is either retried on redelivery or routed to its DLQ topic —
 * either way, the notification service can never silently lose an event.</p>
 *
 * <p><b>Idempotent processing:</b> before dispatching, each handler checks
 * {@link EventDeduplicationService} keyed by the event's own {@code eventId} (the same
 * UUID used as the Kafka message key by the producer). This makes redelivery — which is
 * a normal consequence of at-least-once delivery, not an error condition — safe: a
 * duplicate delivery is detected and skipped rather than causing a duplicate
 * notification to be sent to the user.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;
    private final EventDeduplicationService deduplicationService;

    @KafkaListener(topics = "${app.kafka.topics.wallet-created}", groupId = "${spring.kafka.consumer.group-id}")
    public void onWalletCreated(WalletCreatedEvent event, Acknowledgment ack) {
        process(event.eventId(), () -> notificationService.notifyWalletCreated(event), ack);
    }

    @KafkaListener(topics = "${app.kafka.topics.money-transferred}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMoneyTransferred(MoneyTransferredEvent event, Acknowledgment ack) {
        process(event.eventId(), () -> notificationService.notifyMoneyTransferred(event), ack);
    }

    @KafkaListener(topics = "${app.kafka.topics.money-credited}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMoneyCredited(MoneyCreditedEvent event, Acknowledgment ack) {
        process(event.eventId(), () -> notificationService.notifyMoneyCredited(event), ack);
    }

    @KafkaListener(topics = "${app.kafka.topics.money-debited}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMoneyDebited(MoneyDebitedEvent event, Acknowledgment ack) {
        process(event.eventId(), () -> notificationService.notifyMoneyDebited(event), ack);
    }

    @KafkaListener(topics = "${app.kafka.topics.refund-completed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onRefundCompleted(RefundCompletedEvent event, Acknowledgment ack) {
        process(event.eventId(), () -> notificationService.notifyRefundCompleted(event), ack);
    }

    private void process(java.util.UUID eventId, Runnable handler, Acknowledgment ack) {
        try {
            if (deduplicationService.markSeenIfAbsent(eventId)) {
                handler.run();
            } else {
                log.debug("Skipping already-processed event {}", eventId);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process event {}: {}", eventId, e.getMessage());
            throw e; // let DefaultErrorHandler's retry/DLQ policy take over; do not ack
        }
    }
}

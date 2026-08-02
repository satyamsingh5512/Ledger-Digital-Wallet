package com.walletsys.kafka.producer;

import com.walletsys.config.KafkaTopicsProperties;
import com.walletsys.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes a single outbox event to its resolved Kafka topic.
 *
 * <p>The Kafka message key is the event's own UUID (not the aggregate id), which
 * guarantees per-event ordering is irrelevant (each event is independent) while still
 * giving consumers a stable, unique dedup token — see the notification consumer, which
 * uses this same id to detect and skip re-deliveries.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    public CompletableFuture<Void> publish(OutboxEvent event, Object payload) {
        String topic = topics.resolveTopic(event.getEventType());
        String key = event.getId().toString();

        return kafkaTemplate.send(topic, key, payload)
                .thenAccept(result -> log.debug("Published event {} ({}) to topic={} partition={} offset={}",
                        event.getId(), event.getEventType(), topic,
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset()))
                .exceptionallyCompose(ex -> publishToDlq(event, payload, ex));
    }

    private CompletableFuture<Void> publishToDlq(OutboxEvent event, Object payload, Throwable ex) {
        String dlqTopic = topics.resolveTopic(event.getEventType()) + topics.getDlqSuffix();
        log.error("Failed to publish event {} ({}) to primary topic, routing to DLQ {}: {}",
                event.getId(), event.getEventType(), dlqTopic, ex.getMessage());
        return kafkaTemplate.send(dlqTopic, event.getId().toString(), payload).thenApply(r -> null);
    }
}

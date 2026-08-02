package com.walletsys.kafka.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletsys.entity.OutboxEvent;
import com.walletsys.entity.enums.AggregateType;
import com.walletsys.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes rows to {@code outbox_events} as part of the caller's existing transaction.
 *
 * <p>This is the crux of the transactional outbox pattern: because this method
 * participates in the same {@code @Transactional} boundary as the business mutation
 * (e.g. wallet balance update + ledger append), the event is durably recorded if and
 * only if the business change itself commits. Neither can happen without the other —
 * there is no window where the balance changes but the event is lost, or vice versa.</p>
 */
@Service
@RequiredArgsConstructor
public class OutboxEventWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void write(AggregateType aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(json)
                    .build();
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            // Serialization of a well-typed internal record should never fail; if it does,
            // it's a programming error, not a runtime condition to recover from gracefully.
            throw new IllegalStateException("Failed to serialize outbox event payload for " + eventType, e);
        }
    }
}

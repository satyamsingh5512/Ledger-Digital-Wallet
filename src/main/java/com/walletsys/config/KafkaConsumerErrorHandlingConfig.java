package com.walletsys.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer-side error handling: on listener exception, retry a fixed number of times
 * with backoff, then route the failed record to a {@code <topic>.DLQ} topic via
 * {@link DeadLetterPublishingRecoverer} and acknowledge the original offset so the
 * consumer group can keep making progress instead of getting stuck reprocessing a
 * poison-pill message forever.
 *
 * <p>This is deliberately independent from the producer-side DLQ routing in
 * {@code EventPublisher} — that one handles publish failures (broker unreachable, etc.);
 * this one handles consumer processing failures (a bug in notification logic, a
 * downstream provider outage, malformed payload). Both funnel into the same
 * {@code <topic>.DLQ} naming convention so operators have one place to look.</p>
 */
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerErrorHandlingConfig {

    private final KafkaTopicsProperties topics;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(
                        record.topic() + topics.getDlqSuffix(), record.partition()));

        // 3 retries, 1 second apart, before giving up and dead-lettering.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.setAckAfterHandle(true);
        return handler;
    }
}

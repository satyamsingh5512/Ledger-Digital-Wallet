package com.walletsys.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares all business event topics plus their DLQ counterparts.
 *
 * <p>Partition count of 6 balances parallelism for the notification consumer group
 * against the operational overhead of very high partition counts; this is a starting
 * point that should be revisited based on measured consumer lag, not a hard rule.
 * Replication factor is left at the broker default here (docker-compose runs a single
 * broker) — set explicitly to 3 in any multi-broker/production cluster.</p>
 */
@Configuration
@RequiredArgsConstructor
public class KafkaTopicConfig {

    private final KafkaTopicsProperties topics;

    @Bean
    public NewTopic walletCreatedTopic() {
        return TopicBuilder.name(topics.getWalletCreated()).partitions(6).build();
    }

    @Bean
    public NewTopic moneyTransferredTopic() {
        return TopicBuilder.name(topics.getMoneyTransferred()).partitions(6).build();
    }

    @Bean
    public NewTopic moneyCreditedTopic() {
        return TopicBuilder.name(topics.getMoneyCredited()).partitions(6).build();
    }

    @Bean
    public NewTopic moneyDebitedTopic() {
        return TopicBuilder.name(topics.getMoneyDebited()).partitions(6).build();
    }

    @Bean
    public NewTopic refundCompletedTopic() {
        return TopicBuilder.name(topics.getRefundCompleted()).partitions(6).build();
    }

    @Bean
    public NewTopic walletCreatedDlqTopic() {
        return TopicBuilder.name(topics.getWalletCreated() + topics.getDlqSuffix()).partitions(3).build();
    }

    @Bean
    public NewTopic moneyTransferredDlqTopic() {
        return TopicBuilder.name(topics.getMoneyTransferred() + topics.getDlqSuffix()).partitions(3).build();
    }

    @Bean
    public NewTopic moneyCreditedDlqTopic() {
        return TopicBuilder.name(topics.getMoneyCredited() + topics.getDlqSuffix()).partitions(3).build();
    }

    @Bean
    public NewTopic moneyDebitedDlqTopic() {
        return TopicBuilder.name(topics.getMoneyDebited() + topics.getDlqSuffix()).partitions(3).build();
    }

    @Bean
    public NewTopic refundCompletedDlqTopic() {
        return TopicBuilder.name(topics.getRefundCompleted() + topics.getDlqSuffix()).partitions(3).build();
    }
}

package com.walletsys.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.kafka.topics")
public class KafkaTopicsProperties {

    private String walletCreated;
    private String moneyTransferred;
    private String moneyCredited;
    private String moneyDebited;
    private String refundCompleted;
    private String dlqSuffix;

    /** Resolves the outbox {@code eventType} string to a topic name. */
    public String resolveTopic(String eventType) {
        return switch (eventType) {
            case "WalletCreated" -> walletCreated;
            case "MoneyTransferred" -> moneyTransferred;
            case "MoneyCredited" -> moneyCredited;
            case "MoneyDebited" -> moneyDebited;
            case "RefundCompleted" -> refundCompleted;
            default -> throw new IllegalArgumentException("Unknown outbox event type: " + eventType);
        };
    }
}

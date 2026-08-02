package com.walletsys;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Distributed Ledger & Digital Wallet backend.
 *
 * <p>Design note: this service is intentionally modular-monolith at this stage
 * (single deployable, clearly bounded packages per domain concern). The ledger,
 * wallet, and notification concerns are already separated at the Kafka event
 * boundary so the notification consumer can be peeled off into its own
 * deployable service without changing the producer contract.</p>
 */
@SpringBootApplication
@EnableRetry
@EnableAsync
@EnableScheduling
public class WalletSysApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletSysApplication.class, args);
    }
}

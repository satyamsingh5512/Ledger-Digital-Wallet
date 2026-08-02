package com.walletsys.service.impl;

import com.walletsys.kafka.event.MoneyCreditedEvent;
import com.walletsys.kafka.event.MoneyDebitedEvent;
import com.walletsys.kafka.event.MoneyTransferredEvent;
import com.walletsys.kafka.event.RefundCompletedEvent;
import com.walletsys.kafka.event.WalletCreatedEvent;
import com.walletsys.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stub notification sender — logs what would be sent. In production this would call a
 * transactional email provider (SES/SendGrid), SMS gateway (Twilio/SNS), and/or push
 * notification service (FCM/APNs), likely via a template engine keyed by event type and
 * user locale. Kept synchronous/log-only here since the point of this exercise is the
 * consumer wiring (ack semantics, dedup, DLQ), not the notification content itself.
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    @Override
    public void notifyWalletCreated(WalletCreatedEvent event) {
        log.info("[NOTIFY] Wallet created: walletId={} userId={} currency={}",
                event.walletId(), event.userId(), event.currency());
    }

    @Override
    public void notifyMoneyTransferred(MoneyTransferredEvent event) {
        log.info("[NOTIFY] Money transferred: ref={} from={} to={} amount={} {}",
                event.referenceId(), event.sourceWalletId(), event.destinationWalletId(),
                event.amount(), event.currency());
    }

    @Override
    public void notifyMoneyCredited(MoneyCreditedEvent event) {
        log.info("[NOTIFY] Wallet credited: walletId={} amount={} {} balanceAfter={}",
                event.walletId(), event.amount(), event.currency(), event.balanceAfter());
    }

    @Override
    public void notifyMoneyDebited(MoneyDebitedEvent event) {
        log.info("[NOTIFY] Wallet debited: walletId={} amount={} {} balanceAfter={}",
                event.walletId(), event.amount(), event.currency(), event.balanceAfter());
    }

    @Override
    public void notifyRefundCompleted(RefundCompletedEvent event) {
        log.info("[NOTIFY] Refund completed: refundId={} originalTxn={} amount={} {}",
                event.refundId(), event.originalTransactionId(), event.amount(), event.currency());
    }
}

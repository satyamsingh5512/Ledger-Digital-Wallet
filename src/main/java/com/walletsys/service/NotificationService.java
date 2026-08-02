package com.walletsys.service;

import com.walletsys.kafka.event.MoneyCreditedEvent;
import com.walletsys.kafka.event.MoneyDebitedEvent;
import com.walletsys.kafka.event.MoneyTransferredEvent;
import com.walletsys.kafka.event.RefundCompletedEvent;
import com.walletsys.kafka.event.WalletCreatedEvent;

/**
 * Simulates dispatching a user-facing notification (email / SMS / push) for each
 * business event. This implementation only logs — swapping in a real provider (SES,
 * Twilio, FCM, etc.) means implementing this interface differently; nothing in the
 * Kafka consumer layer needs to change.
 */
public interface NotificationService {

    void notifyWalletCreated(WalletCreatedEvent event);

    void notifyMoneyTransferred(MoneyTransferredEvent event);

    void notifyMoneyCredited(MoneyCreditedEvent event);

    void notifyMoneyDebited(MoneyDebitedEvent event);

    void notifyRefundCompleted(RefundCompletedEvent event);
}

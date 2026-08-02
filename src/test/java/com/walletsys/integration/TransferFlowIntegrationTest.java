package com.walletsys.integration;

import com.walletsys.dto.request.CreateWalletRequest;
import com.walletsys.dto.request.CreditRequest;
import com.walletsys.dto.request.DebitRequest;
import com.walletsys.dto.request.RefundRequest;
import com.walletsys.dto.request.RegisterRequest;
import com.walletsys.dto.request.TransferRequest;
import com.walletsys.dto.response.RefundResponse;
import com.walletsys.dto.response.TransactionResponse;
import com.walletsys.dto.response.WalletResponse;
import com.walletsys.entity.enums.TransactionStatus;
import com.walletsys.service.RefundService;
import com.walletsys.service.TransferService;
import com.walletsys.service.UserService;
import com.walletsys.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the full money-movement lifecycle, exercised through
 * the real service layer against actual Postgres/Kafka/Redis (via Testcontainers) —
 * proving the transactional outbox, ledger, and idempotency mechanisms work together
 * correctly outside of mocks.
 */
class TransferFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private WalletService walletService;
    @Autowired
    private TransferService transferService;
    @Autowired
    private RefundService refundService;

    private UUID registerUser(String email) {
        var user = userService.register(RegisterRequest.builder()
                .email(email)
                .password("Password123")
                .fullName("Test User")
                .build());
        return user.getId();
    }

    @Test
    void fullLifecycle_creditThenTransferThenRefund_settlesLedgerCorrectly() {
        UUID payerId = registerUser("payer-" + UUID.randomUUID() + "@example.com");
        UUID payeeId = registerUser("payee-" + UUID.randomUUID() + "@example.com");

        WalletResponse payerWallet = walletService.createWallet(payerId, CreateWalletRequest.builder().currency("INR").build());
        WalletResponse payeeWallet = walletService.createWallet(payeeId, CreateWalletRequest.builder().currency("INR").build());

        // 1. Credit the payer wallet with initial funds
        TransactionResponse creditTxn = transferService.credit(payerId,
                CreditRequest.builder().walletId(payerWallet.getId()).amount(new BigDecimal("500.00")).currency("INR").build(),
                "credit-key-" + UUID.randomUUID());
        assertThat(creditTxn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);

        WalletResponse payerAfterCredit = walletService.getWallet(payerWallet.getId(), payerId);
        assertThat(payerAfterCredit.getBalance()).isEqualByComparingTo("500.00");

        // 2. Transfer from payer to payee
        TransactionResponse transferTxn = transferService.transfer(payerId,
                TransferRequest.builder()
                        .sourceWalletId(payerWallet.getId())
                        .destinationWalletId(payeeWallet.getId())
                        .amount(new BigDecimal("200.00"))
                        .currency("INR")
                        .build(),
                "transfer-key-" + UUID.randomUUID());
        assertThat(transferTxn.getStatus()).isEqualTo(TransactionStatus.COMPLETED);

        assertThat(walletService.getWallet(payerWallet.getId(), payerId).getBalance()).isEqualByComparingTo("300.00");
        assertThat(walletService.getWallet(payeeWallet.getId(), payeeId).getBalance()).isEqualByComparingTo("200.00");

        // 3. Refund the transfer in full
        RefundResponse refund = refundService.refund(payerId,
                RefundRequest.builder().originalTransactionId(transferTxn.getId()).reason("customer request").build(),
                "refund-key-" + UUID.randomUUID());

        assertThat(walletService.getWallet(payerWallet.getId(), payerId).getBalance()).isEqualByComparingTo("500.00");
        assertThat(walletService.getWallet(payeeWallet.getId(), payeeId).getBalance()).isEqualByComparingTo("0.00");

        TransactionResponse originalAfterRefund = transferService.getTransaction(transferTxn.getId(), payerId);
        assertThat(originalAfterRefund.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(refund.getRefundTransactionId()).isNotNull();
    }

    @Test
    void debit_reducesBalance_andRejectsWhenInsufficientFunds() {
        UUID userId = registerUser("debit-user-" + UUID.randomUUID() + "@example.com");
        WalletResponse wallet = walletService.createWallet(userId, CreateWalletRequest.builder().currency("INR").build());

        transferService.credit(userId,
                CreditRequest.builder().walletId(wallet.getId()).amount(new BigDecimal("50.00")).currency("INR").build(),
                "seed-" + UUID.randomUUID());

        transferService.debit(userId,
                DebitRequest.builder().walletId(wallet.getId()).amount(new BigDecimal("30.00")).currency("INR").build(),
                "debit-" + UUID.randomUUID());

        assertThat(walletService.getWallet(wallet.getId(), userId).getBalance()).isEqualByComparingTo("20.00");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                transferService.debit(userId,
                        DebitRequest.builder().walletId(wallet.getId()).amount(new BigDecimal("999.00")).currency("INR").build(),
                        "debit-overdraw-" + UUID.randomUUID())))
                .isInstanceOf(com.walletsys.exception.InsufficientBalanceException.class);

        // balance unaffected by the rejected debit
        assertThat(walletService.getWallet(wallet.getId(), userId).getBalance()).isEqualByComparingTo("20.00");
    }

    @Test
    void transfer_retriedWithSameIdempotencyKey_doesNotDoubleApply() {
        UUID payerId = registerUser("idem-payer-" + UUID.randomUUID() + "@example.com");
        UUID payeeId = registerUser("idem-payee-" + UUID.randomUUID() + "@example.com");

        WalletResponse payerWallet = walletService.createWallet(payerId, CreateWalletRequest.builder().currency("INR").build());
        WalletResponse payeeWallet = walletService.createWallet(payeeId, CreateWalletRequest.builder().currency("INR").build());

        transferService.credit(payerId,
                CreditRequest.builder().walletId(payerWallet.getId()).amount(new BigDecimal("100.00")).currency("INR").build(),
                "seed-" + UUID.randomUUID());

        String idempotencyKey = "same-key-" + UUID.randomUUID();
        TransferRequest transferRequest = TransferRequest.builder()
                .sourceWalletId(payerWallet.getId())
                .destinationWalletId(payeeWallet.getId())
                .amount(new BigDecimal("40.00"))
                .currency("INR")
                .build();

        TransactionResponse first = transferService.transfer(payerId, transferRequest, idempotencyKey);
        TransactionResponse replay = transferService.transfer(payerId, transferRequest, idempotencyKey);

        assertThat(replay.getId()).isEqualTo(first.getId());
        // balance must reflect exactly ONE transfer of 40, not two
        assertThat(walletService.getWallet(payerWallet.getId(), payerId).getBalance()).isEqualByComparingTo("60.00");
        assertThat(walletService.getWallet(payeeWallet.getId(), payeeId).getBalance()).isEqualByComparingTo("40.00");
    }
}

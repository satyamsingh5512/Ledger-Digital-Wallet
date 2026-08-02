package com.walletsys.unit.service;

import com.walletsys.dto.request.RefundRequest;
import com.walletsys.entity.LedgerEntry;
import com.walletsys.entity.Refund;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.User;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.RefundStatus;
import com.walletsys.entity.enums.TransactionStatus;
import com.walletsys.entity.enums.TransactionType;
import com.walletsys.exception.RefundNotAllowedException;
import com.walletsys.kafka.outbox.OutboxEventWriter;
import com.walletsys.mapper.RefundMapper;
import com.walletsys.repository.RefundRepository;
import com.walletsys.repository.TransactionRepository;
import com.walletsys.service.LedgerService;
import com.walletsys.service.impl.RefundAttemptExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundAttemptExecutorTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private RefundMapper refundMapper;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    private RefundAttemptExecutor executor;

    private UUID initiatorId;
    private Wallet sourceWallet;
    private Wallet destinationWallet;
    private Transaction originalTransfer;

    @BeforeEach
    void setUp() {
        executor = new RefundAttemptExecutor(transactionRepository, refundRepository, ledgerService,
                refundMapper, outboxEventWriter);

        initiatorId = UUID.randomUUID();
        User initiator = User.builder().build();
        initiator.setId(initiatorId);

        sourceWallet = Wallet.builder().user(initiator).currency("INR").balance(BigDecimal.ZERO).build();
        sourceWallet.setId(UUID.randomUUID());

        User counterparty = User.builder().build();
        counterparty.setId(UUID.randomUUID());
        destinationWallet = Wallet.builder().user(counterparty).currency("INR").balance(BigDecimal.ZERO).build();
        destinationWallet.setId(UUID.randomUUID());

        originalTransfer = Transaction.builder()
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .sourceWallet(sourceWallet)
                .destinationWallet(destinationWallet)
                .amount(new BigDecimal("100.00"))
                .currency("INR")
                .build();
        originalTransfer.setId(UUID.randomUUID());
    }

    @Test
    void doRefund_rejectsWhenOriginalTransactionNotCompleted() {
        originalTransfer.setStatus(TransactionStatus.PENDING);
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        RefundRequest request = RefundRequest.builder()
                .originalTransactionId(originalTransfer.getId())
                .reason("test")
                .build();

        assertThatThrownBy(() -> executor.doRefund(initiatorId, request))
                .isInstanceOf(RefundNotAllowedException.class);

        verifyNoInteractions(ledgerService);
    }

    @Test
    void doRefund_rejectsRefundingARefundTransaction() {
        originalTransfer.setType(TransactionType.REFUND);
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        RefundRequest request = RefundRequest.builder()
                .originalTransactionId(originalTransfer.getId())
                .reason("test")
                .build();

        assertThatThrownBy(() -> executor.doRefund(initiatorId, request))
                .isInstanceOf(RefundNotAllowedException.class);
    }

    @Test
    void doRefund_rejectsWhenNonParticipantInitiates() {
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        RefundRequest request = RefundRequest.builder()
                .originalTransactionId(originalTransfer.getId())
                .reason("test")
                .build();

        assertThatThrownBy(() -> executor.doRefund(UUID.randomUUID(), request))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void doRefund_rejectsWhenCumulativeRefundsWouldExceedOriginalAmount() {
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));

        Refund existingRefund = Refund.builder()
                .originalTransaction(originalTransfer)
                .amount(new BigDecimal("80.00"))
                .status(RefundStatus.COMPLETED)
                .build();
        when(refundRepository.findByOriginalTransactionId(originalTransfer.getId()))
                .thenReturn(List.of(existingRefund));

        RefundRequest request = RefundRequest.builder()
                .originalTransactionId(originalTransfer.getId())
                .amount(new BigDecimal("30.00")) // 80 + 30 > 100
                .reason("test")
                .build();

        assertThatThrownBy(() -> executor.doRefund(initiatorId, request))
                .isInstanceOf(RefundNotAllowedException.class);

        verifyNoInteractions(ledgerService);
    }

    @Test
    void doRefund_fullRefund_reversesTransferLedgerEffectAndMarksOriginalReversed() {
        when(transactionRepository.findById(originalTransfer.getId())).thenReturn(Optional.of(originalTransfer));
        when(refundRepository.findByOriginalTransactionId(originalTransfer.getId())).thenReturn(List.of());
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            return t;
        });
        when(ledgerService.debit(any(), any(), any(), any())).thenReturn(mock(LedgerEntry.class));
        when(ledgerService.credit(any(), any(), any(), any())).thenReturn(mock(LedgerEntry.class));

        RefundRequest request = RefundRequest.builder()
                .originalTransactionId(originalTransfer.getId())
                .reason("customer requested")
                .build(); // no amount -> full refund

        executor.doRefund(initiatorId, request);

        // TRANSFER reversal: debit the (original) destination, credit the (original) source
        verify(ledgerService).debit(org.mockito.ArgumentMatchers.eq(destinationWallet), any(Transaction.class), any(BigDecimal.class), any());
        verify(ledgerService).credit(org.mockito.ArgumentMatchers.eq(sourceWallet), any(Transaction.class), any(BigDecimal.class), any());
        verify(outboxEventWriter).write(any(), any(), org.mockito.ArgumentMatchers.eq("RefundCompleted"), any());
    }
}

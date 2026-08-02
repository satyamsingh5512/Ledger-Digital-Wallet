package com.walletsys.service.impl;

import com.walletsys.dto.request.RefundRequest;
import com.walletsys.dto.response.RefundResponse;
import com.walletsys.entity.Refund;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.AggregateType;
import com.walletsys.entity.enums.RefundStatus;
import com.walletsys.entity.enums.TransactionStatus;
import com.walletsys.entity.enums.TransactionType;
import com.walletsys.exception.RefundNotAllowedException;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.kafka.event.RefundCompletedEvent;
import com.walletsys.kafka.outbox.OutboxEventWriter;
import com.walletsys.mapper.RefundMapper;
import com.walletsys.repository.RefundRepository;
import com.walletsys.repository.TransactionRepository;
import com.walletsys.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Single-attempt, transactional/retryable refund logic. Factored out of
 * {@code RefundServiceImpl} for the same reason as {@link TransferAttemptExecutor}:
 * self-invocation within one bean bypasses the Spring AOP proxy, silently disabling
 * {@code @Retryable}/{@code @Transactional}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundAttemptExecutor {

    private final TransactionRepository transactionRepository;
    private final RefundRepository refundRepository;
    private final LedgerService ledgerService;
    private final RefundMapper refundMapper;
    private final OutboxEventWriter outboxEventWriter;

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = 30,
            backoff = @Backoff(delay = 100, maxDelay = 2000, multiplier = 2.0, random = true)
    )
    @Transactional
    public RefundResponse doRefund(UUID initiatingUserId, RefundRequest request) {
        Transaction original = transactionRepository.findById(request.getOriginalTransactionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Transaction not found: " + request.getOriginalTransactionId()));

        assertParticipant(original, initiatingUserId);

        if (original.getStatus() != TransactionStatus.COMPLETED) {
            throw new RefundNotAllowedException(
                    "Transaction " + original.getId() + " is not COMPLETED (status=" + original.getStatus() + ")");
        }
        if (original.getType() == TransactionType.REFUND) {
            throw new RefundNotAllowedException("Cannot refund a REFUND transaction");
        }

        BigDecimal refundAmount = request.getAmount() != null ? request.getAmount() : original.getAmount();

        List<Refund> existingRefunds = refundRepository.findByOriginalTransactionId(original.getId());
        BigDecimal alreadyRefunded = existingRefunds.stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (alreadyRefunded.add(refundAmount).compareTo(original.getAmount()) > 0) {
            throw new RefundNotAllowedException(
                    "Refund amount exceeds remaining refundable balance for transaction " + original.getId()
                            + " (already refunded=" + alreadyRefunded + ", original=" + original.getAmount() + ")");
        }

        Refund refund = refundRepository.save(Refund.builder()
                .originalTransaction(original)
                .amount(refundAmount)
                .reason(request.getReason())
                .status(RefundStatus.PENDING)
                .build());

        Transaction refundTransaction = reverseLedgerEffect(original, refundAmount);

        refund.setRefundTransaction(refundTransaction);
        refund.setStatus(RefundStatus.COMPLETED);
        Refund completedRefund = refundRepository.save(refund);

        if (alreadyRefunded.add(refundAmount).compareTo(original.getAmount()) == 0) {
            original.setStatus(TransactionStatus.REVERSED);
            transactionRepository.save(original);
        }

        outboxEventWriter.write(AggregateType.REFUND, completedRefund.getId(), "RefundCompleted",
                new RefundCompletedEvent(UUID.randomUUID(), completedRefund.getId(), original.getId(),
                        refundTransaction.getId(), refundAmount, original.getCurrency(), Instant.now()));

        log.info("Refund completed refundId={} originalTxn={} refundTxn={} amount={}",
                completedRefund.getId(), original.getId(), refundTransaction.getId(), refundAmount);

        return refundMapper.toResponse(completedRefund);
    }

    private Transaction reverseLedgerEffect(Transaction original, BigDecimal amount) {
        Transaction refundTransaction = transactionRepository.save(Transaction.builder()
                .referenceId("RFD-" + Instant.now().toEpochMilli() + "-"
                        + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .type(TransactionType.REFUND)
                .status(TransactionStatus.PENDING)
                .sourceWallet(original.getDestinationWallet())
                .destinationWallet(original.getSourceWallet())
                .amount(amount)
                .currency(original.getCurrency())
                .build());

        switch (original.getType()) {
            case TRANSFER -> {
                Wallet originalSource = original.getSourceWallet();
                Wallet originalDestination = original.getDestinationWallet();
                ledgerService.debit(originalDestination, refundTransaction, amount,
                        "Refund reversal of transaction " + original.getId());
                ledgerService.credit(originalSource, refundTransaction, amount,
                        "Refund reversal of transaction " + original.getId());
            }
            case CREDIT -> {
                Wallet credited = original.getDestinationWallet();
                ledgerService.debit(credited, refundTransaction, amount,
                        "Refund reversal of credit " + original.getId());
            }
            case DEBIT -> {
                Wallet debited = original.getSourceWallet();
                ledgerService.credit(debited, refundTransaction, amount,
                        "Refund reversal of debit " + original.getId());
            }
            default -> throw new RefundNotAllowedException(
                    "Unsupported original transaction type for refund: " + original.getType());
        }

        refundTransaction.setStatus(TransactionStatus.COMPLETED);
        return transactionRepository.save(refundTransaction);
    }

    private void assertParticipant(Transaction transaction, UUID requestingUserId) {
        boolean isSource = transaction.getSourceWallet() != null
                && transaction.getSourceWallet().getUser().getId().equals(requestingUserId);
        boolean isDestination = transaction.getDestinationWallet() != null
                && transaction.getDestinationWallet().getUser().getId().equals(requestingUserId);
        if (!isSource && !isDestination) {
            throw new AccessDeniedException("You do not have access to transaction " + transaction.getId());
        }
    }
}

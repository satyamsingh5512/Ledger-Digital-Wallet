package com.walletsys.service.impl;

import com.walletsys.dto.request.CreditRequest;
import com.walletsys.dto.request.DebitRequest;
import com.walletsys.dto.request.TransferRequest;
import com.walletsys.dto.response.TransactionResponse;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.AggregateType;
import com.walletsys.entity.enums.TransactionStatus;
import com.walletsys.entity.enums.TransactionType;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.exception.WalletNotActiveException;
import com.walletsys.kafka.event.MoneyCreditedEvent;
import com.walletsys.kafka.event.MoneyDebitedEvent;
import com.walletsys.kafka.event.MoneyTransferredEvent;
import com.walletsys.kafka.outbox.OutboxEventWriter;
import com.walletsys.mapper.TransactionMapper;
import com.walletsys.repository.TransactionRepository;
import com.walletsys.repository.WalletRepository;
import com.walletsys.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Holds the single-attempt, {@code @Transactional} + {@code @Retryable} money-movement
 * operations, deliberately factored out of {@link TransferServiceImpl} into its own
 * Spring bean.
 *
 * <p>This separation exists because Spring AOP proxies (both {@code @Transactional} and
 * {@code @Retryable}) only intercept calls that arrive <em>through the proxy</em> — a
 * method calling another {@code @Retryable} method on {@code this} bypasses the proxy
 * entirely (self-invocation), silently disabling the retry behavior. Placing these
 * methods on a distinct, separately-injected bean guarantees every call goes through the
 * proxy and the retry/transactional advice actually applies.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferAttemptExecutor {

    private static final int MAX_RETRY_ATTEMPTS = 30;

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final TransactionMapper transactionMapper;
    private final OutboxEventWriter outboxEventWriter;

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 100, maxDelay = 2000, multiplier = 2.0, random = true)
    )
    @Transactional
    public TransactionResponse doTransfer(UUID initiatingUserId, TransferRequest request) {
        Wallet source = requireOwnedActiveWallet(request.getSourceWalletId(), initiatingUserId);
        Wallet destination = requireActiveWallet(request.getDestinationWalletId());

        if (!source.getCurrency().equals(destination.getCurrency())
                || !source.getCurrency().equals(request.getCurrency())) {
            throw new IllegalArgumentException("Currency mismatch between wallets and request");
        }

        Transaction transaction = transactionRepository.save(Transaction.builder()
                .referenceId(generateReferenceId("TRF"))
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .sourceWallet(source)
                .destinationWallet(destination)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build());

        ledgerService.debit(source, transaction, request.getAmount(), "Transfer to " + destination.getId());
        ledgerService.credit(destination, transaction, request.getAmount(), "Transfer from " + source.getId());

        transaction.setStatus(TransactionStatus.COMPLETED);
        Transaction completed = transactionRepository.save(transaction);

        outboxEventWriter.write(AggregateType.TRANSACTION, completed.getId(), "MoneyTransferred",
                new MoneyTransferredEvent(UUID.randomUUID(), completed.getId(), completed.getReferenceId(),
                        source.getId(), destination.getId(), request.getAmount(), request.getCurrency(), Instant.now()));

        log.info("Transfer completed txn={} ref={} from={} to={} amount={}",
                completed.getId(), completed.getReferenceId(), source.getId(), destination.getId(), request.getAmount());

        return transactionMapper.toResponse(completed);
    }

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 100, maxDelay = 2000, multiplier = 2.0, random = true)
    )
    @Transactional
    public TransactionResponse doCredit(UUID initiatingUserId, CreditRequest request) {
        Wallet wallet = requireOwnedActiveWallet(request.getWalletId(), initiatingUserId);

        Transaction transaction = transactionRepository.save(Transaction.builder()
                .referenceId(generateReferenceId("CRD"))
                .type(TransactionType.CREDIT)
                .status(TransactionStatus.PENDING)
                .destinationWallet(wallet)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build());

        var entry = ledgerService.credit(wallet, transaction, request.getAmount(),
                request.getNote() != null ? request.getNote() : "Wallet credit");

        transaction.setStatus(TransactionStatus.COMPLETED);
        Transaction completed = transactionRepository.save(transaction);

        outboxEventWriter.write(AggregateType.TRANSACTION, completed.getId(), "MoneyCredited",
                new MoneyCreditedEvent(UUID.randomUUID(), completed.getId(), wallet.getId(),
                        request.getAmount(), entry.getBalanceAfter(), request.getCurrency(), Instant.now()));

        log.info("Credit completed txn={} wallet={} amount={}", completed.getId(), wallet.getId(), request.getAmount());
        return transactionMapper.toResponse(completed);
    }

    @Retryable(
            retryFor = ConcurrencyFailureException.class,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 100, maxDelay = 2000, multiplier = 2.0, random = true)
    )
    @Transactional
    public TransactionResponse doDebit(UUID initiatingUserId, DebitRequest request) {
        Wallet wallet = requireOwnedActiveWallet(request.getWalletId(), initiatingUserId);

        Transaction transaction = transactionRepository.save(Transaction.builder()
                .referenceId(generateReferenceId("DBT"))
                .type(TransactionType.DEBIT)
                .status(TransactionStatus.PENDING)
                .sourceWallet(wallet)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build());

        var entry = ledgerService.debit(wallet, transaction, request.getAmount(),
                request.getNote() != null ? request.getNote() : "Wallet debit");

        transaction.setStatus(TransactionStatus.COMPLETED);
        Transaction completed = transactionRepository.save(transaction);

        outboxEventWriter.write(AggregateType.TRANSACTION, completed.getId(), "MoneyDebited",
                new MoneyDebitedEvent(UUID.randomUUID(), completed.getId(), wallet.getId(),
                        request.getAmount(), entry.getBalanceAfter(), request.getCurrency(), Instant.now()));

        log.info("Debit completed txn={} wallet={} amount={}", completed.getId(), wallet.getId(), request.getAmount());
        return transactionMapper.toResponse(completed);
    }

    private Wallet requireOwnedActiveWallet(UUID walletId, UUID ownerUserId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getId().equals(ownerUserId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not have access to wallet " + walletId);
        }
        if (!wallet.isActive()) {
            throw new WalletNotActiveException("Wallet " + walletId + " is not active (status=" + wallet.getStatus() + ")");
        }
        return wallet;
    }

    private Wallet requireActiveWallet(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.isActive()) {
            throw new WalletNotActiveException("Wallet " + walletId + " is not active (status=" + wallet.getStatus() + ")");
        }
        return wallet;
    }

    private String generateReferenceId(String prefix) {
        return prefix + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}

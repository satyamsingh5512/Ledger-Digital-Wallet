package com.walletsys.service.impl;

import com.walletsys.entity.LedgerEntry;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.LedgerEntryType;
import com.walletsys.exception.InsufficientBalanceException;
import com.walletsys.repository.LedgerEntryRepository;
import com.walletsys.repository.WalletRepository;
import com.walletsys.service.LedgerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Default implementation of {@link LedgerService}.
 *
 * <p><b>Double spend prevention:</b> the balance check (`balance >= amount`) and the
 * balance decrement happen against the same in-memory {@link Wallet} instance, inside
 * one {@code @Transactional} method, and the eventual {@code UPDATE} is guarded by the
 * JPA {@code @Version} column. Two concurrent debits against the same wallet will both
 * read a consistent snapshot, but only one commits — Hibernate's version check turns the
 * loser's {@code UPDATE ... WHERE id = ? AND version = ?} into a zero-row update, which
 * it surfaces as {@code OptimisticLockException}. That exception propagates up to
 * TransferService's retry loop, which re-reads the wallet (now with the winner's
 * committed balance) and re-evaluates the balance check — so the second debit either
 * succeeds against the new balance or correctly fails with
 * {@code InsufficientBalanceException} if funds are now insufficient. This is what
 * makes double-spending structurally impossible rather than merely unlikely.</p>
 *
 * <p>{@code Propagation.MANDATORY} is used deliberately: this method must never run
 * outside of a caller's existing transaction (e.g. TransferService's), because a debit
 * and its paired credit must commit or roll back together.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerServiceImpl implements LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry debit(Wallet wallet, Transaction transaction, BigDecimal amount, String description) {
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                    "Wallet " + wallet.getId() + " has insufficient balance: available="
                            + wallet.getBalance() + ", required=" + amount);
        }

        BigDecimal newBalance = wallet.getBalance().subtract(amount);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);
        // Flush the wallet UPDATE immediately, on its own, rather than letting Hibernate
        // defer and batch it together with the subsequent ledger_entry INSERT. Under
        // heavy concurrent contention on the same wallet row, batching the wallet UPDATE
        // together with other statements widens the window in which Postgres can observe
        // conflicting lock-acquisition orders across transactions, manifesting as
        // deadlocks (detected and retried, but wasteful) rather than clean, fast
        // optimistic-lock version conflicts. Flushing here keeps the row lock's lifetime
        // as short as possible and isolates the version-check failure mode.
        flushTranslatingExceptions();

        LedgerEntry entry = LedgerEntry.builder()
                .transaction(transaction)
                .wallet(wallet)
                .entryType(LedgerEntryType.DEBIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .currency(wallet.getCurrency())
                .description(description)
                .build();

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        log.info("Ledger DEBIT wallet={} amount={} balanceAfter={} txn={}",
                wallet.getId(), amount, newBalance, transaction.getId());
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry credit(Wallet wallet, Transaction transaction, BigDecimal amount, String description) {
        BigDecimal newBalance = wallet.getBalance().add(amount);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);
        flushTranslatingExceptions(); // see debit() for why this flush is deliberate

        LedgerEntry entry = LedgerEntry.builder()
                .transaction(transaction)
                .wallet(wallet)
                .entryType(LedgerEntryType.CREDIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .currency(wallet.getCurrency())
                .description(description)
                .build();

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        log.info("Ledger CREDIT wallet={} amount={} balanceAfter={} txn={}",
                wallet.getId(), amount, newBalance, transaction.getId());
        return saved;
    }

    /**
     * Flushes the current Hibernate session, translating any raw
     * {@code jakarta.persistence.PersistenceException} (including
     * {@code OptimisticLockException} and lock-acquisition/deadlock failures) into
     * Spring's {@code org.springframework.dao} exception hierarchy.
     *
     * <p>This translation normally happens automatically for exceptions that cross a
     * Spring Data repository proxy boundary (via
     * {@code PersistenceExceptionTranslationPostProcessor}), but an explicit,
     * manually-invoked {@code entityManager.flush()} inside a plain {@code @Service}
     * bypasses that proxy entirely — without this explicit translation, a deadlock here
     * would surface as a raw, untranslated {@code jakarta.persistence.OptimisticLockException}
     * that {@code TransferAttemptExecutor}'s {@code @Retryable(retryFor =
     * ConcurrencyFailureException.class)} would never recognize as retryable.</p>
     */
    private void flushTranslatingExceptions() {
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            var translated = EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(e);
            throw translated != null ? translated : e;
        }
    }
}

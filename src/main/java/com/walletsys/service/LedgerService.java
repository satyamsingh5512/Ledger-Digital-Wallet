package com.walletsys.service;

import com.walletsys.entity.LedgerEntry;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.LedgerEntryType;

import java.math.BigDecimal;

/**
 * Appends immutable ledger entries and applies their accounting effect to the wallet's
 * cached balance, in a single unit of work.
 *
 * <p>This is the ONLY place in the codebase that is allowed to mutate
 * {@code wallet.balance} or insert into {@code ledger_entries}. Every other service
 * (TransferService, RefundService, wallet credit/debit endpoints) must go through this
 * interface, which keeps the double-entry invariant (sum of signed ledger amounts for a
 * transaction is zero) enforced in one place instead of duplicated across callers.</p>
 */
public interface LedgerService {

    /**
     * Applies a DEBIT to the wallet: decreases {@code wallet.balance} and appends a
     * DEBIT {@link LedgerEntry}. Throws {@link com.walletsys.exception.InsufficientBalanceException}
     * if the wallet does not have enough balance.
     *
     * @return the created ledger entry, with {@code balanceAfter} populated
     */
    LedgerEntry debit(Wallet wallet, Transaction transaction, BigDecimal amount, String description);

    /**
     * Applies a CREDIT to the wallet: increases {@code wallet.balance} and appends a
     * CREDIT {@link LedgerEntry}.
     *
     * @return the created ledger entry, with {@code balanceAfter} populated
     */
    LedgerEntry credit(Wallet wallet, Transaction transaction, BigDecimal amount, String description);

    /** Entry type helper retained for callers that need to branch on direction generically. */
    default LedgerEntryType oppositeOf(LedgerEntryType type) {
        return type == LedgerEntryType.DEBIT ? LedgerEntryType.CREDIT : LedgerEntryType.DEBIT;
    }
}

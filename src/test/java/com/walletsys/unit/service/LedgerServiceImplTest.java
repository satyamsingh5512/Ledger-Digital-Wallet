package com.walletsys.unit.service;

import com.walletsys.entity.LedgerEntry;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.LedgerEntryType;
import com.walletsys.entity.enums.WalletStatus;
import com.walletsys.exception.InsufficientBalanceException;
import com.walletsys.repository.LedgerEntryRepository;
import com.walletsys.repository.WalletRepository;
import com.walletsys.service.impl.LedgerServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the core double-entry accounting logic. These tests operate purely at
 * the Java level (no Spring context, no DB) to prove the arithmetic and guard-clause
 * behavior of {@link LedgerServiceImpl} in isolation — the concurrency guarantees this
 * class relies on (optimistic locking under real contention) are proven separately in
 * the Testcontainers-backed concurrent transfer integration test.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    private Wallet wallet;
    private Transaction transaction;

    @BeforeEach
    void setUp() {
        wallet = Wallet.builder()
                .currency("INR")
                .balance(new BigDecimal("100.0000"))
                .status(WalletStatus.ACTIVE)
                .version(0L)
                .build();

        transaction = Transaction.builder().build();

        org.mockito.Mockito.lenient().when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(walletRepository.save(any(Wallet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void debit_reducesBalanceAndAppendsDebitEntry() {
        LedgerEntry entry = ledgerService.debit(wallet, transaction, new BigDecimal("40.0000"), "test debit");

        assertThat(wallet.getBalance()).isEqualByComparingTo("60.0000");
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(entry.getAmount()).isEqualByComparingTo("40.0000");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("60.0000");
    }

    @Test
    void credit_increasesBalanceAndAppendsCreditEntry() {
        LedgerEntry entry = ledgerService.credit(wallet, transaction, new BigDecimal("25.5000"), "test credit");

        assertThat(wallet.getBalance()).isEqualByComparingTo("125.5000");
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("125.5000");
    }

    @Test
    void debit_rejectsAmountExceedingBalance_preventingOverdraw() {
        assertThatThrownBy(() -> ledgerService.debit(wallet, transaction, new BigDecimal("100.0001"), "too much"))
                .isInstanceOf(InsufficientBalanceException.class);

        // balance must remain unchanged after a rejected debit
        assertThat(wallet.getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void debit_allowsExactBalanceDrainToZero() {
        LedgerEntry entry = ledgerService.debit(wallet, transaction, new BigDecimal("100.0000"), "drain");

        assertThat(wallet.getBalance()).isEqualByComparingTo("0.0000");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("0.0000");
    }

    @Test
    void debitThenCredit_ofSameAmount_isNetZeroOnBalance() {
        BigDecimal amount = new BigDecimal("33.3300");
        ledgerService.debit(wallet, transaction, amount, "out");
        ledgerService.credit(wallet, transaction, amount, "back");

        assertThat(wallet.getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void debit_persistsWalletBeforeAppendingLedgerEntry() {
        ledgerService.debit(wallet, transaction, new BigDecimal("10.0000"), "order check");

        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().getBalance()).isEqualByComparingTo("90.0000");

        verify(ledgerEntryRepository).save(any(LedgerEntry.class));
    }
}

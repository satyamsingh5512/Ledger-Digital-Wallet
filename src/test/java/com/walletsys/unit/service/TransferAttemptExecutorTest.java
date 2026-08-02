package com.walletsys.unit.service;

import com.walletsys.dto.request.TransferRequest;
import com.walletsys.entity.LedgerEntry;
import com.walletsys.entity.Transaction;
import com.walletsys.entity.User;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.WalletStatus;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.exception.WalletNotActiveException;
import com.walletsys.kafka.outbox.OutboxEventWriter;
import com.walletsys.mapper.TransactionMapper;
import com.walletsys.repository.TransactionRepository;
import com.walletsys.repository.WalletRepository;
import com.walletsys.service.LedgerService;
import com.walletsys.service.impl.TransferAttemptExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferAttemptExecutorTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private TransactionMapper transactionMapper;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    private TransferAttemptExecutor executor;

    private UUID ownerId;
    private Wallet sourceWallet;
    private Wallet destinationWallet;

    @BeforeEach
    void setUp() {
        executor = new TransferAttemptExecutor(walletRepository, transactionRepository, ledgerService,
                transactionMapper, outboxEventWriter);

        ownerId = UUID.randomUUID();
        User owner = User.builder().build();
        owner.setId(ownerId);

        sourceWallet = Wallet.builder().user(owner).currency("INR")
                .balance(new BigDecimal("500.0000")).status(WalletStatus.ACTIVE).build();
        sourceWallet.setId(UUID.randomUUID());

        User destinationOwner = User.builder().build();
        destinationOwner.setId(UUID.randomUUID());
        destinationWallet = Wallet.builder().user(destinationOwner)
                .currency("INR").balance(new BigDecimal("10.0000")).status(WalletStatus.ACTIVE).build();
        destinationWallet.setId(UUID.randomUUID());
    }

    @Test
    void doTransfer_rejectsWhenSourceWalletNotOwnedByInitiatingUser() {
        UUID someoneElse = UUID.randomUUID();
        when(walletRepository.findById(sourceWallet.getId())).thenReturn(Optional.of(sourceWallet));

        TransferRequest request = TransferRequest.builder()
                .sourceWalletId(sourceWallet.getId())
                .destinationWalletId(destinationWallet.getId())
                .amount(new BigDecimal("10.00"))
                .currency("INR")
                .build();

        assertThatThrownBy(() -> executor.doTransfer(someoneElse, request))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(ledgerService);
    }

    @Test
    void doTransfer_rejectsWhenSourceWalletNotFound() {
        UUID missingWalletId = UUID.randomUUID();
        when(walletRepository.findById(missingWalletId)).thenReturn(Optional.empty());

        TransferRequest request = TransferRequest.builder()
                .sourceWalletId(missingWalletId)
                .destinationWalletId(destinationWallet.getId())
                .amount(new BigDecimal("10.00"))
                .currency("INR")
                .build();

        assertThatThrownBy(() -> executor.doTransfer(ownerId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void doTransfer_rejectsWhenSourceWalletIsFrozen() {
        sourceWallet.setStatus(WalletStatus.FROZEN);
        when(walletRepository.findById(sourceWallet.getId())).thenReturn(Optional.of(sourceWallet));

        TransferRequest request = TransferRequest.builder()
                .sourceWalletId(sourceWallet.getId())
                .destinationWalletId(destinationWallet.getId())
                .amount(new BigDecimal("10.00"))
                .currency("INR")
                .build();

        assertThatThrownBy(() -> executor.doTransfer(ownerId, request))
                .isInstanceOf(WalletNotActiveException.class);

        verifyNoInteractions(ledgerService);
    }

    @Test
    void doTransfer_rejectsWhenDestinationWalletIsClosed() {
        destinationWallet.setStatus(WalletStatus.CLOSED);
        when(walletRepository.findById(sourceWallet.getId())).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findById(destinationWallet.getId())).thenReturn(Optional.of(destinationWallet));

        TransferRequest request = TransferRequest.builder()
                .sourceWalletId(sourceWallet.getId())
                .destinationWalletId(destinationWallet.getId())
                .amount(new BigDecimal("10.00"))
                .currency("INR")
                .build();

        assertThatThrownBy(() -> executor.doTransfer(ownerId, request))
                .isInstanceOf(WalletNotActiveException.class);
    }

    @Test
    void doTransfer_rejectsCurrencyMismatchBetweenWalletsAndRequest() {
        when(walletRepository.findById(sourceWallet.getId())).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findById(destinationWallet.getId())).thenReturn(Optional.of(destinationWallet));

        TransferRequest request = TransferRequest.builder()
                .sourceWalletId(sourceWallet.getId())
                .destinationWalletId(destinationWallet.getId())
                .amount(new BigDecimal("10.00"))
                .currency("USD") // wallets are INR
                .build();

        assertThatThrownBy(() -> executor.doTransfer(ownerId, request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(ledgerService);
    }

    @Test
    void doTransfer_happyPath_debitsSourceAndCreditsDestinationAndPublishesEvent() {
        when(walletRepository.findById(sourceWallet.getId())).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findById(destinationWallet.getId())).thenReturn(Optional.of(destinationWallet));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(UUID.randomUUID());
            }
            return t;
        });
        when(ledgerService.debit(any(), any(), any(), any())).thenReturn(mock(LedgerEntry.class));
        when(ledgerService.credit(any(), any(), any(), any())).thenReturn(mock(LedgerEntry.class));

        TransferRequest request = TransferRequest.builder()
                .sourceWalletId(sourceWallet.getId())
                .destinationWalletId(destinationWallet.getId())
                .amount(new BigDecimal("50.00"))
                .currency("INR")
                .build();

        executor.doTransfer(ownerId, request);

        verify(ledgerService).debit(org.mockito.ArgumentMatchers.eq(sourceWallet), any(Transaction.class), any(BigDecimal.class), any());
        verify(ledgerService).credit(org.mockito.ArgumentMatchers.eq(destinationWallet), any(Transaction.class), any(BigDecimal.class), any());
        verify(outboxEventWriter).write(any(), any(), org.mockito.ArgumentMatchers.eq("MoneyTransferred"), any());
    }
}

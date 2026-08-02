package com.walletsys.integration;

import com.walletsys.dto.request.CreateWalletRequest;
import com.walletsys.dto.request.CreditRequest;
import com.walletsys.dto.request.TransferRequest;
import com.walletsys.dto.request.RegisterRequest;
import com.walletsys.dto.response.WalletResponse;
import com.walletsys.exception.InsufficientBalanceException;
import com.walletsys.service.TransferService;
import com.walletsys.service.UserService;
import com.walletsys.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves that concurrent transfers against the same source wallet cannot overdraw it —
 * the core double-spend-prevention guarantee of the optimistic-locking + retry design
 * (see LedgerServiceImpl / TransferAttemptExecutor javadoc for the mechanism).
 *
 * <p>Setup: one wallet is funded with exactly enough balance for N of the M concurrent
 * transfer attempts (M &gt; N) fired simultaneously via a fixed thread pool released by a
 * {@link CountDownLatch} barrier, to maximize actual contention on the same DB row rather
 * than relying on thread scheduling luck. Assertion: exactly N attempts succeed, the rest
 * fail with {@link InsufficientBalanceException} (not corruption, not silent loss), and
 * the wallet's final balance is exactly zero — never negative.</p>
 *
 * <p>The outbox poller is disabled for this test class specifically: it runs on its own
 * schedule against the same database and, under this test's deliberately extreme
 * contention on a single row, was found to be an additional (correctness-irrelevant)
 * source of Postgres lock contention that made the test itself flaky without indicating
 * any actual bug in the transfer path. Disabling it here isolates what this test is
 * actually meant to prove: that TransferAttemptExecutor's own retry loop, unassisted,
 * correctly serializes concurrent writers without ever overdrawing the wallet.</p>
 */
@TestPropertySource(properties = "app.outbox.poller-enabled=false")
class ConcurrentTransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private WalletService walletService;
    @Autowired
    private TransferService transferService;

    @Test
    void concurrentTransfers_exceedingBalance_neverOverdrawTheSourceWallet() throws InterruptedException {
        UUID payerId = registerUser("concurrent-payer-" + UUID.randomUUID() + "@example.com");
        WalletResponse payerWallet = walletService.createWallet(payerId, CreateWalletRequest.builder().currency("INR").build());

        int successfulTransferCapacity = 3; // wallet will be funded for exactly this many 10.00 transfers
        BigDecimal transferAmount = new BigDecimal("10.00");
        transferService.credit(payerId,
                CreditRequest.builder()
                        .walletId(payerWallet.getId())
                        .amount(transferAmount.multiply(BigDecimal.valueOf(successfulTransferCapacity)))
                        .currency("INR")
                        .build(),
                "seed-" + UUID.randomUUID());

        int totalConcurrentAttempts = 6; // deliberately more than the wallet can afford
        List<UUID> payeeWalletIds = createPayeeWallets(totalConcurrentAttempts);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientBalanceCount = new AtomicInteger();
        AtomicInteger unexpectedFailureCount = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(totalConcurrentAttempts);
        CountDownLatch startBarrier = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalConcurrentAttempts);

        for (UUID payeeWalletId : payeeWalletIds) {
            executor.submit(() -> {
                try {
                    startBarrier.await();
                    transferService.transfer(payerId,
                            TransferRequest.builder()
                                    .sourceWalletId(payerWallet.getId())
                                    .destinationWalletId(payeeWalletId)
                                    .amount(transferAmount)
                                    .currency("INR")
                                    .build(),
                            "concurrent-" + UUID.randomUUID());
                    successCount.incrementAndGet();
                } catch (InsufficientBalanceException e) {
                    insufficientBalanceCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedFailureCount.incrementAndGet();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startBarrier.countDown(); // release all threads simultaneously to maximize contention
        boolean completedInTime = completionLatch.await(180, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).as("all concurrent attempts should finish within timeout").isTrue();
        assertThat(unexpectedFailureCount.get())
                .as("no attempt should fail with anything other than InsufficientBalanceException")
                .isZero();
        assertThat(successCount.get()).as("exactly the affordable number of transfers should succeed")
                .isEqualTo(successfulTransferCapacity);
        assertThat(insufficientBalanceCount.get())
                .isEqualTo(totalConcurrentAttempts - successfulTransferCapacity);

        // The invariant that matters most: balance must never go negative, and must land
        // at exactly zero (successfulTransferCapacity * transferAmount fully consumed).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            WalletResponse finalWallet = walletService.getWallet(payerWallet.getId(), payerId);
            assertThat(finalWallet.getBalance()).isEqualByComparingTo("0.00");
        });
    }

    private UUID registerUser(String email) {
        var user = userService.register(RegisterRequest.builder()
                .email(email)
                .password("Password123")
                .fullName("Concurrent Test User")
                .build());
        return user.getId();
    }

    private List<UUID> createPayeeWallets(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> {
                    UUID payeeUserId = registerUser("concurrent-payee-" + UUID.randomUUID() + "@example.com");
                    return walletService.createWallet(payeeUserId, CreateWalletRequest.builder().currency("INR").build()).getId();
                })
                .toList();
    }
}

package com.walletsys.repository;

import com.walletsys.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Wallet persistence.
 *
 * <p><b>Locking strategy:</b> we deliberately do NOT use {@code PESSIMISTIC_WRITE} here.
 * At 20M-user scale, a handful of "hot" wallets (e.g. a popular merchant receiving many
 * concurrent transfers) would serialize every writer behind a row lock, capping
 * throughput and creating lock-wait queues that amplify latency tail. Instead we rely on
 * JPA's automatic optimistic locking via {@code @Version}: every {@code UPDATE wallets
 * ... WHERE id = ? AND version = ?} either succeeds (and bumps the version) or affects
 * zero rows, at which point Hibernate throws {@code OptimisticLockException}. The service
 * layer catches this and retries the whole business operation with fresh data
 * (see TransferService), which scales far better under contention because failed
 * attempts don't hold any lock while retrying.</p>
 *
 * <p>{@link #findByIdForUpdate} is provided for the rare case for we explicitly want a
 * short-lived pessimistic lock (currently unused by TransferService, but useful for
 * batch/admin reconciliation jobs where retry-storms are undesirable and contention is
 * expected to be low).</p>
 */
@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByUserId(UUID userId);

    Optional<Wallet> findByUserIdAndCurrency(UUID userId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);
}

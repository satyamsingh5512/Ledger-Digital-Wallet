package com.walletsys.repository;

import com.walletsys.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only access to the immutable ledger. There is intentionally no update/delete
 * method on this repository — {@code JpaRepository} technically exposes them, but the
 * service layer never calls them, and the DB triggers reject them regardless. The only
 * mutation this repository is used for is {@code save} on a brand-new entry (INSERT).
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    @Query("SELECT COALESCE(SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) " +
           "FROM LedgerEntry le WHERE le.wallet.id = :walletId")
    java.math.BigDecimal computeBalanceFromLedger(@Param("walletId") UUID walletId);
}

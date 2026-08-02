package com.walletsys.repository;

import com.walletsys.entity.Transaction;
import com.walletsys.entity.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByReferenceId(String referenceId);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.sourceWallet.id = :walletId OR t.destinationWallet.id = :walletId
            ORDER BY t.createdAt DESC
            """)
    Page<Transaction> findHistoryForWallet(@Param("walletId") UUID walletId, Pageable pageable);

    @Query("""
            SELECT t FROM Transaction t
            WHERE (t.sourceWallet.id = :walletId OR t.destinationWallet.id = :walletId)
              AND t.status = :status
            ORDER BY t.createdAt DESC
            """)
    Page<Transaction> findHistoryForWalletByStatus(
            @Param("walletId") UUID walletId,
            @Param("status") TransactionStatus status,
            Pageable pageable);
}

package com.walletsys.entity;

import com.walletsys.entity.enums.WalletStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * A wallet holds a cached balance in a single currency for a single user.
 *
 * <p><b>Concurrency model:</b> {@code balance} is a materialized projection of the
 * append-only {@link LedgerEntry} stream — it is never the source of truth, but every
 * read-heavy operation (balance check, transfer pre-check) reads it directly rather than
 * aggregating the ledger, because the ledger can grow to hundreds of millions of rows per
 * wallet over a wallet's lifetime.</p>
 *
 * <p>Every write to {@code balance} happens in the same DB transaction that appends the
 * corresponding {@link LedgerEntry} rows, and is guarded by {@link #version} (JPA
 * {@code @Version} — optimistic locking). Under concurrent transfers touching the same
 * wallet, the losing transaction gets an {@code OptimisticLockException} and is retried
 * by the service layer (see TransferService) rather than blocking behind a pessimistic
 * row lock, which would serialize all activity on a hot wallet and cap throughput.</p>
 */
@Entity
@Table(name = "wallets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = "user")
@EqualsAndHashCode(callSuper = true)
public class Wallet extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WalletStatus status = WalletStatus.ACTIVE;

    /** Optimistic lock token. Prevents lost updates / double-spend under concurrency. */
    @Version
    @Column(nullable = false)
    private Long version;

    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }
}

package com.walletsys.entity;

import com.walletsys.entity.enums.LedgerEntryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * A single, immutable double-entry ledger row — the source of truth for all balances.
 *
 * <p><b>Immutability contract:</b> once persisted, a ledger entry is never updated or
 * deleted. This is enforced twice, deliberately redundantly:</p>
 * <ol>
 *   <li>At the application layer: this entity exposes no setters (all fields are
 *       {@code final}-by-convention via the builder; there is no {@code @Setter}). Any
 *       correction is modeled as a new, compensating entry — never a mutation.</li>
 *   <li>At the database layer: {@code trg_ledger_entries_no_update} /
 *       {@code trg_ledger_entries_no_delete} triggers reject the operation outright, so
 *       even a raw SQL statement or a future bug bypassing the ORM cannot rewrite
 *       history.</li>
 * </ol>
 *
 * <p>Every business transaction produces a <em>balanced</em> pair (or more) of entries —
 * e.g. a transfer produces one {@code DEBIT} on the source wallet and one {@code CREDIT}
 * on the destination wallet, both referencing the same {@link Transaction}, both for the
 * same amount. Summed with sign, the ledger always nets to zero across all wallets.</p>
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode(callSuper = true)
public class LedgerEntry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10, updatable = false)
    private LedgerEntryType entryType;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    /** Snapshot of the wallet balance immediately after this entry — audit/replay aid. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(length = 255, updatable = false)
    private String description;
}

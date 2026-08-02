package com.walletsys.cache;

import com.walletsys.dto.response.WalletResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed cache for wallet balances — the single hottest read in the system
 * (every transfer pre-check, every balance-check API call). Cache invalidation happens
 * synchronously, in the same service-layer call that commits a balance mutation, so
 * readers never observe a cached value older than the last committed write from this
 * node. (In a multi-node deployment there is a small window where another node's write
 * invalidates the cache slightly after its DB commit — acceptable here because the DB
 * row, not the cache, is always the point of truth for the next write's optimistic-lock
 * check; a stale cache read can at worst show a slightly outdated balance for a moment,
 * never cause an incorrect mutation.)
 */
public interface WalletCacheService {

    Optional<WalletResponse> get(UUID walletId);

    void put(WalletResponse wallet);

    void evict(UUID walletId);
}

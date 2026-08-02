package com.walletsys.service;

import com.walletsys.dto.request.CreateWalletRequest;
import com.walletsys.dto.response.WalletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface WalletService {

    WalletResponse createWallet(UUID userId, CreateWalletRequest request);

    WalletResponse getWallet(UUID walletId, UUID requestingUserId);

    List<WalletResponse> listWallets(UUID userId);

    /** Balance read path — served from Redis cache when available (see WalletCacheService). */
    WalletResponse getBalance(UUID walletId, UUID requestingUserId);

    org.springframework.data.domain.Page<com.walletsys.dto.response.LedgerEntryResponse> getStatement(
            UUID walletId, UUID requestingUserId, Pageable pageable);
}

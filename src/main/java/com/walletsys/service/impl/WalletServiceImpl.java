package com.walletsys.service.impl;

import com.walletsys.cache.WalletCacheService;
import com.walletsys.dto.request.CreateWalletRequest;
import com.walletsys.dto.response.LedgerEntryResponse;
import com.walletsys.dto.response.WalletResponse;
import com.walletsys.entity.User;
import com.walletsys.entity.Wallet;
import com.walletsys.entity.enums.AggregateType;
import com.walletsys.entity.enums.WalletStatus;
import com.walletsys.exception.DuplicateResourceException;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.kafka.event.WalletCreatedEvent;
import com.walletsys.kafka.outbox.OutboxEventWriter;
import com.walletsys.mapper.LedgerEntryMapper;
import com.walletsys.mapper.WalletMapper;
import com.walletsys.repository.LedgerEntryRepository;
import com.walletsys.repository.UserRepository;
import com.walletsys.repository.WalletRepository;
import com.walletsys.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletMapper walletMapper;
    private final LedgerEntryMapper ledgerEntryMapper;
    private final WalletCacheService walletCacheService;
    private final OutboxEventWriter outboxEventWriter;

    @Override
    @Transactional
    public WalletResponse createWallet(UUID userId, CreateWalletRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        walletRepository.findByUserIdAndCurrency(userId, request.getCurrency()).ifPresent(w -> {
            throw new DuplicateResourceException(
                    "User already has a " + request.getCurrency() + " wallet");
        });

        Wallet wallet = Wallet.builder()
                .user(user)
                .currency(request.getCurrency())
                .balance(BigDecimal.ZERO)
                .status(WalletStatus.ACTIVE)
                .build();

        Wallet saved = walletRepository.save(wallet);

        outboxEventWriter.write(
                AggregateType.WALLET,
                saved.getId(),
                "WalletCreated",
                new WalletCreatedEvent(UUID.randomUUID(), saved.getId(), userId,
                        saved.getCurrency(), saved.getBalance(), Instant.now()));

        log.info("Created wallet id={} userId={} currency={}", saved.getId(), userId, saved.getCurrency());
        return walletMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID walletId, UUID requestingUserId) {
        Wallet wallet = loadOwnedWallet(walletId, requestingUserId);
        return walletMapper.toResponse(wallet);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletResponse> listWallets(UUID userId) {
        return walletRepository.findByUserId(userId).stream()
                .map(walletMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getBalance(UUID walletId, UUID requestingUserId) {
        return walletCacheService.get(walletId)
                .filter(cached -> cached.getUserId().equals(requestingUserId))
                .orElseGet(() -> {
                    WalletResponse fresh = getWallet(walletId, requestingUserId);
                    walletCacheService.put(fresh);
                    return fresh;
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> getStatement(UUID walletId, UUID requestingUserId, Pageable pageable) {
        loadOwnedWallet(walletId, requestingUserId); // ownership check
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId, pageable)
                .map(ledgerEntryMapper::toResponse);
    }

    private Wallet loadOwnedWallet(UUID walletId, UUID requestingUserId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You do not have access to wallet " + walletId);
        }
        return wallet;
    }
}

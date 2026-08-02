package com.walletsys.controller;

import com.walletsys.dto.request.CreateWalletRequest;
import com.walletsys.dto.response.ApiResponse;
import com.walletsys.dto.response.LedgerEntryResponse;
import com.walletsys.dto.response.PageResponse;
import com.walletsys.dto.response.WalletResponse;
import com.walletsys.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
@Tag(name = "Wallets", description = "Wallet creation, balance, and statement retrieval")
public class WalletController {

    private final WalletService walletService;

    @PostMapping
    @Operation(summary = "Create a new wallet in the given currency for the authenticated user")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = walletService.createWallet(AuthenticatedUser.currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, "Wallet created successfully"));
    }

    @GetMapping
    @Operation(summary = "List all wallets owned by the authenticated user")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> listWallets() {
        List<WalletResponse> response = walletService.listWallets(AuthenticatedUser.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Get wallet details by id")
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(@PathVariable UUID walletId) {
        WalletResponse response = walletService.getWallet(walletId, AuthenticatedUser.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{walletId}/balance")
    @Operation(summary = "Get the current balance for a wallet (served from Redis cache when available)")
    public ResponseEntity<ApiResponse<WalletResponse>> getBalance(@PathVariable UUID walletId) {
        WalletResponse response = walletService.getBalance(walletId, AuthenticatedUser.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{walletId}/statement")
    @Operation(summary = "Get the immutable ledger statement for a wallet, most recent first")
    public ResponseEntity<ApiResponse<PageResponse<LedgerEntryResponse>>> getStatement(
            @PathVariable UUID walletId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var result = walletService.getStatement(walletId, AuthenticatedUser.currentUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(result)));
    }
}

package com.walletsys.controller;

import com.walletsys.dto.request.CreditRequest;
import com.walletsys.dto.request.DebitRequest;
import com.walletsys.dto.request.TransferRequest;
import com.walletsys.dto.response.ApiResponse;
import com.walletsys.dto.response.PageResponse;
import com.walletsys.dto.response.TransactionResponse;
import com.walletsys.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Money-movement endpoints: transfer between wallets, credit (top-up), debit
 * (withdrawal). Every mutating operation here requires a client-generated
 * {@code Idempotency-Key} header — see IdempotencyService for the exactly-once
 * semantics this provides across client retries.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Transactions", description = "Transfers, credits, debits, and transaction history")
public class TransactionController {

    private final TransferService transferService;

    @PostMapping("/transfer")
    @Operation(summary = "Transfer money between two wallets",
            description = "Requires an Idempotency-Key header. Retrying with the same key returns the original result.")
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        TransactionResponse response = transferService.transfer(AuthenticatedUser.currentUserId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, "Transfer completed successfully"));
    }

    @PostMapping("/credit")
    @Operation(summary = "Credit (top up) a wallet",
            description = "Requires an Idempotency-Key header. Retrying with the same key returns the original result.")
    public ResponseEntity<ApiResponse<TransactionResponse>> credit(
            @Valid @RequestBody CreditRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        TransactionResponse response = transferService.credit(AuthenticatedUser.currentUserId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, "Credit completed successfully"));
    }

    @PostMapping("/debit")
    @Operation(summary = "Debit (withdraw from) a wallet",
            description = "Requires an Idempotency-Key header. Retrying with the same key returns the original result.")
    public ResponseEntity<ApiResponse<TransactionResponse>> debit(
            @Valid @RequestBody DebitRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        TransactionResponse response = transferService.debit(AuthenticatedUser.currentUserId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, "Debit completed successfully"));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a transaction by id")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(@PathVariable UUID transactionId) {
        TransactionResponse response = transferService.getTransaction(transactionId, AuthenticatedUser.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/wallet/{walletId}/history")
    @Operation(summary = "Get paginated transaction history for a wallet, most recent first")
    public ResponseEntity<ApiResponse<PageResponse<TransactionResponse>>> getHistory(
            @PathVariable UUID walletId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var result = transferService.getHistory(walletId, AuthenticatedUser.currentUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(result)));
    }
}

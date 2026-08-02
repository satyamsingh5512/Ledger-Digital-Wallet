package com.walletsys.service.impl;

import com.walletsys.dto.request.CreditRequest;
import com.walletsys.dto.request.DebitRequest;
import com.walletsys.dto.request.TransferRequest;
import com.walletsys.dto.response.TransactionResponse;
import com.walletsys.entity.Transaction;
import com.walletsys.exception.IdempotencyKeyConflictException;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.mapper.TransactionMapper;
import com.walletsys.repository.TransactionRepository;
import com.walletsys.repository.WalletRepository;
import com.walletsys.service.TransferService;
import com.walletsys.idempotency.IdempotencyOutcome;
import com.walletsys.idempotency.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Thin orchestrator: wraps each money-movement operation with the idempotency-key
 * protocol, then delegates the actual transactional/retryable work to
 * {@link TransferAttemptExecutor}. See that class for why the retry logic lives in a
 * separate bean.
 */
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private final TransferAttemptExecutor attemptExecutor;
    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;
    private final WalletRepository walletRepository;
    private final IdempotencyService idempotencyService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public TransactionResponse transfer(UUID initiatingUserId, TransferRequest request, String idempotencyKey) {
        return withIdempotency(idempotencyKey, "/api/v1/transactions/transfer", request,
                () -> attemptExecutor.doTransfer(initiatingUserId, request));
    }

    @Override
    public TransactionResponse credit(UUID initiatingUserId, CreditRequest request, String idempotencyKey) {
        return withIdempotency(idempotencyKey, "/api/v1/transactions/credit", request,
                () -> attemptExecutor.doCredit(initiatingUserId, request));
    }

    @Override
    public TransactionResponse debit(UUID initiatingUserId, DebitRequest request, String idempotencyKey) {
        return withIdempotency(idempotencyKey, "/api/v1/transactions/debit", request,
                () -> attemptExecutor.doDebit(initiatingUserId, request));
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId, UUID requestingUserId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        boolean isSource = transaction.getSourceWallet() != null
                && transaction.getSourceWallet().getUser().getId().equals(requestingUserId);
        boolean isDestination = transaction.getDestinationWallet() != null
                && transaction.getDestinationWallet().getUser().getId().equals(requestingUserId);
        if (!isSource && !isDestination) {
            throw new AccessDeniedException("You do not have access to transaction " + transactionId);
        }

        return transactionMapper.toResponse(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getHistory(UUID walletId, UUID requestingUserId, Pageable pageable) {
        var wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You do not have access to wallet " + walletId);
        }
        return transactionRepository.findHistoryForWallet(walletId, pageable).map(transactionMapper::toResponse);
    }

    /**
     * Wraps a business operation with the idempotency-key protocol: replay on duplicate,
     * persist the response on success, mark failed on exception (so the client can retry
     * with the same key rather than being stuck behind a permanently-cached failure).
     */
    private TransactionResponse withIdempotency(String idempotencyKey, String endpoint, Object requestBody,
                                                 Supplier<TransactionResponse> operation) {
        IdempotencyOutcome outcome = idempotencyService.reserve(idempotencyKey, endpoint, requestBody);

        if (outcome.isReplay()) {
            return readJson(outcome.cachedResponseBody(), TransactionResponse.class);
        }
        if (outcome.isInProgress()) {
            throw new IdempotencyKeyConflictException(
                    "A request with Idempotency-Key '" + idempotencyKey + "' is already being processed");
        }

        try {
            TransactionResponse response = operation.get();
            idempotencyService.complete(idempotencyKey, 200, writeJson(response));
            return response;
        } catch (RuntimeException e) {
            idempotencyService.markFailed(idempotencyKey);
            throw e;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response for idempotency cache", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached idempotent response", e);
        }
    }
}

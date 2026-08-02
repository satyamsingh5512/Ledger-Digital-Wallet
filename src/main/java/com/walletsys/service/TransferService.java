package com.walletsys.service;

import com.walletsys.dto.request.CreditRequest;
import com.walletsys.dto.request.DebitRequest;
import com.walletsys.dto.request.TransferRequest;
import com.walletsys.dto.response.TransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Orchestrates the critical money-movement path: transfers, credits (top-ups), and
 * debits (withdrawals). Every method here is idempotent given the same
 * {@code idempotencyKey}, and internally retries on optimistic-lock contention.
 */
public interface TransferService {

    TransactionResponse transfer(UUID initiatingUserId, TransferRequest request, String idempotencyKey);

    TransactionResponse credit(UUID initiatingUserId, CreditRequest request, String idempotencyKey);

    TransactionResponse debit(UUID initiatingUserId, DebitRequest request, String idempotencyKey);

    TransactionResponse getTransaction(UUID transactionId, UUID requestingUserId);

    Page<TransactionResponse> getHistory(UUID walletId, UUID requestingUserId, Pageable pageable);
}

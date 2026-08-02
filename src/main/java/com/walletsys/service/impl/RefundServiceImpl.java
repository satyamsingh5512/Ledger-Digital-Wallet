package com.walletsys.service.impl;

import com.walletsys.dto.request.RefundRequest;
import com.walletsys.dto.response.RefundResponse;
import com.walletsys.entity.Refund;
import com.walletsys.entity.Transaction;
import com.walletsys.exception.IdempotencyKeyConflictException;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.idempotency.IdempotencyOutcome;
import com.walletsys.idempotency.IdempotencyService;
import com.walletsys.mapper.RefundMapper;
import com.walletsys.repository.RefundRepository;
import com.walletsys.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Thin orchestrator: applies the idempotency-key protocol, then delegates the actual
 * transactional/retryable refund work to {@link RefundAttemptExecutor}.
 */
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final RefundAttemptExecutor attemptExecutor;
    private final RefundRepository refundRepository;
    private final RefundMapper refundMapper;
    private final IdempotencyService idempotencyService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public RefundResponse refund(UUID initiatingUserId, RefundRequest request, String idempotencyKey) {
        IdempotencyOutcome outcome = idempotencyService.reserve(idempotencyKey, "/api/v1/refunds", request);

        if (outcome.isReplay()) {
            return readJson(outcome.cachedResponseBody());
        }
        if (outcome.isInProgress()) {
            throw new IdempotencyKeyConflictException(
                    "A refund request with Idempotency-Key '" + idempotencyKey + "' is already being processed");
        }

        try {
            RefundResponse response = attemptExecutor.doRefund(initiatingUserId, request);
            idempotencyService.complete(idempotencyKey, 200, writeJson(response));
            return response;
        } catch (RuntimeException e) {
            idempotencyService.markFailed(idempotencyKey);
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RefundResponse getRefund(UUID refundId, UUID requestingUserId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found: " + refundId));

        Transaction original = refund.getOriginalTransaction();
        boolean isSource = original.getSourceWallet() != null
                && original.getSourceWallet().getUser().getId().equals(requestingUserId);
        boolean isDestination = original.getDestinationWallet() != null
                && original.getDestinationWallet().getUser().getId().equals(requestingUserId);
        if (!isSource && !isDestination) {
            throw new AccessDeniedException("You do not have access to refund " + refundId);
        }

        return refundMapper.toResponse(refund);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize refund response for idempotency cache", e);
        }
    }

    private RefundResponse readJson(String json) {
        try {
            return objectMapper.readValue(json, RefundResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached idempotent refund response", e);
        }
    }
}

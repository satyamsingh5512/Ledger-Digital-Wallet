package com.walletsys.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletsys.entity.IdempotencyKey;
import com.walletsys.entity.enums.IdempotencyStatus;
import com.walletsys.exception.IdempotencyKeyConflictException;
import com.walletsys.repository.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Centralizes the idempotency-key protocol used by every mutating financial endpoint
 * (transfer, credit, debit, refund).
 *
 * <p><b>Protocol:</b></p>
 * <ol>
 *   <li>Client sends {@code Idempotency-Key: <uuid>} header with a mutating request.</li>
 *   <li>We compute a SHA-256 hash of the canonical request body and try to INSERT a row
 *       {@code (idempotency_key, request_hash, status=IN_PROGRESS)}, relying on the
 *       unique constraint on {@code idempotency_key} to detect a duplicate atomically —
 *       this uses a separate, immediately-committed transaction
 *       ({@code Propagation.REQUIRES_NEW}) so the reservation is visible to concurrent
 *       requests even while the caller's own business transaction is still open.</li>
 *   <li>If the insert succeeds, this is a first attempt: the caller proceeds with the
 *       operation and later calls {@link #complete} to store the final response.</li>
 *   <li>If the insert fails on the unique constraint, we fetch the existing row:
 *       <ul>
 *         <li>same {@code request_hash} + status {@code COMPLETED} → replay the stored
 *             response verbatim (idempotent retry, safe to repeat indefinitely).</li>
 *         <li>same {@code request_hash} + status {@code IN_PROGRESS} → another request
 *             with the same key is still being processed (race); caller should surface a
 *             409/425-style "processing, retry later" response.</li>
 *         <li>different {@code request_hash} → client reused a key for a different
 *             payload — this is a client bug, surfaced as {@link IdempotencyKeyConflictException}.</li>
 *       </ul>
 *   </li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final long TTL_HOURS = 24;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public String hashRequest(Object requestBody) {
        try {
            String canonical = objectMapper.writeValueAsString(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash idempotency request payload", e);
        }
    }

    /**
     * Attempts to reserve the idempotency key for a new operation.
     *
     * @return {@link IdempotencyOutcome#firstAttempt()} if this is a new key, or
     *         {@link IdempotencyOutcome#replay(String, int)} if a completed response
     *         already exists and should be replayed verbatim.
     * @throws IdempotencyKeyConflictException if the key is reused with a different payload
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyOutcome reserve(String idempotencyKey, String endpoint, Object requestBody) {
        String requestHash = hashRequest(requestBody);

        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), requestHash);
        }

        try {
            IdempotencyKey key = IdempotencyKey.builder()
                    .idempotencyKey(idempotencyKey)
                    .requestHash(requestHash)
                    .endpoint(endpoint)
                    .status(IdempotencyStatus.IN_PROGRESS)
                    .expiresAt(Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS))
                    .build();
            idempotencyKeyRepository.save(key);
            entityManager.flush(); // force the unique-constraint violation to surface here, not later
            return IdempotencyOutcome.firstAttempt();
        } catch (DataIntegrityViolationException e) {
            // Lost the race to a concurrent request with the same key between our SELECT and INSERT.
            IdempotencyKey raced = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
            return handleExisting(raced, requestHash);
        }
    }

    private IdempotencyOutcome handleExisting(IdempotencyKey existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency-Key '" + existing.getIdempotencyKey()
                            + "' was already used with a different request payload");
        }

        return switch (existing.getStatus()) {
            case COMPLETED -> IdempotencyOutcome.replay(existing.getResponseBody(), existing.getResponseStatus());
            case IN_PROGRESS -> IdempotencyOutcome.inProgress();
            case FAILED -> IdempotencyOutcome.firstAttempt(); // allow retry of a previously failed attempt
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String idempotencyKey, int responseStatus, String responseBodyJson) {
        idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey).ifPresent(key -> {
            key.setStatus(IdempotencyStatus.COMPLETED);
            key.setResponseStatus(responseStatus);
            key.setResponseBody(responseBodyJson);
            idempotencyKeyRepository.save(key);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String idempotencyKey) {
        idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey).ifPresent(key -> {
            key.setStatus(IdempotencyStatus.FAILED);
            idempotencyKeyRepository.save(key);
        });
    }
}

package com.walletsys.unit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletsys.entity.IdempotencyKey;
import com.walletsys.entity.enums.IdempotencyStatus;
import com.walletsys.exception.IdempotencyKeyConflictException;
import com.walletsys.idempotency.IdempotencyOutcome;
import com.walletsys.idempotency.IdempotencyService;
import com.walletsys.repository.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private EntityManager entityManager;

    private IdempotencyService idempotencyService;

    private static final String KEY = "test-key-123";
    private static final String ENDPOINT = "/api/v1/transactions/transfer";
    private record SampleRequest(String field) {}

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(idempotencyKeyRepository, new ObjectMapper(), entityManager);
    }

    @Test
    void reserve_firstAttempt_returnsFirstAttemptOutcome() {
        when(idempotencyKeyRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        IdempotencyOutcome outcome = idempotencyService.reserve(KEY, ENDPOINT, new SampleRequest("a"));

        assertThat(outcome.status()).isEqualTo(IdempotencyOutcome.Status.FIRST_ATTEMPT);
    }

    @Test
    void reserve_existingCompletedKeyWithMatchingHash_replaysStoredResponse() {
        String requestHash = idempotencyService.hashRequest(new SampleRequest("a"));
        IdempotencyKey existing = IdempotencyKey.builder()
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .endpoint(ENDPOINT)
                .status(IdempotencyStatus.COMPLETED)
                .responseStatus(200)
                .responseBody("{\"result\":\"ok\"}")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        IdempotencyOutcome outcome = idempotencyService.reserve(KEY, ENDPOINT, new SampleRequest("a"));

        assertThat(outcome.isReplay()).isTrue();
        assertThat(outcome.cachedResponseBody()).isEqualTo("{\"result\":\"ok\"}");
    }

    @Test
    void reserve_existingInProgressKey_returnsInProgressOutcome() {
        String requestHash = idempotencyService.hashRequest(new SampleRequest("a"));
        IdempotencyKey existing = IdempotencyKey.builder()
                .idempotencyKey(KEY)
                .requestHash(requestHash)
                .endpoint(ENDPOINT)
                .status(IdempotencyStatus.IN_PROGRESS)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        IdempotencyOutcome outcome = idempotencyService.reserve(KEY, ENDPOINT, new SampleRequest("a"));

        assertThat(outcome.isInProgress()).isTrue();
    }

    @Test
    void reserve_existingKeyWithDifferentPayloadHash_throwsConflict() {
        IdempotencyKey existing = IdempotencyKey.builder()
                .idempotencyKey(KEY)
                .requestHash("a-totally-different-hash")
                .endpoint(ENDPOINT)
                .status(IdempotencyStatus.COMPLETED)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.reserve(KEY, ENDPOINT, new SampleRequest("a")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void reserve_raceLosingInsert_fallsBackToReadingWinnersRow() {
        when(idempotencyKeyRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty()) // initial check: nothing yet
                .thenReturn(Optional.of(IdempotencyKey.builder() // after losing the race
                        .idempotencyKey(KEY)
                        .requestHash(idempotencyService.hashRequest(new SampleRequest("a")))
                        .endpoint(ENDPOINT)
                        .status(IdempotencyStatus.IN_PROGRESS)
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build()));
        doThrow(new DataIntegrityViolationException("unique violation"))
                .when(entityManager).flush();

        IdempotencyOutcome outcome = idempotencyService.reserve(KEY, ENDPOINT, new SampleRequest("a"));

        assertThat(outcome.isInProgress()).isTrue();
    }

    @Test
    void hashRequest_isDeterministicForSamePayload() {
        String hash1 = idempotencyService.hashRequest(new SampleRequest("same"));
        String hash2 = idempotencyService.hashRequest(new SampleRequest("same"));
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashRequest_differsForDifferentPayload() {
        String hash1 = idempotencyService.hashRequest(new SampleRequest("one"));
        String hash2 = idempotencyService.hashRequest(new SampleRequest("two"));
        assertThat(hash1).isNotEqualTo(hash2);
    }
}

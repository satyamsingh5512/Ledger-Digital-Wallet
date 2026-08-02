package com.walletsys.idempotency;

/** Outcome of {@link IdempotencyService#reserve}. Exactly one of the three states holds. */
public record IdempotencyOutcome(Status status, String cachedResponseBody, Integer cachedResponseStatus) {

    public enum Status { FIRST_ATTEMPT, REPLAY, IN_PROGRESS }

    public static IdempotencyOutcome firstAttempt() {
        return new IdempotencyOutcome(Status.FIRST_ATTEMPT, null, null);
    }

    public static IdempotencyOutcome replay(String responseBody, Integer responseStatus) {
        return new IdempotencyOutcome(Status.REPLAY, responseBody, responseStatus);
    }

    public static IdempotencyOutcome inProgress() {
        return new IdempotencyOutcome(Status.IN_PROGRESS, null, null);
    }

    public boolean isReplay() {
        return status == Status.REPLAY;
    }

    public boolean isInProgress() {
        return status == Status.IN_PROGRESS;
    }
}

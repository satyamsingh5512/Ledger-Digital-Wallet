package com.walletsys.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Uniform error envelope returned by {@code GlobalExceptionHandler} for every non-2xx
 * response. {@code errorCode} is a stable, machine-readable identifier (e.g.
 * {@code INSUFFICIENT_BALANCE}) that clients can branch on, independent of the
 * human-readable {@code message}, which may change wording over time.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private boolean success;
    private String errorCode;
    private String message;
    private int status;
    private String path;

    /** Field-level validation errors, e.g. {"amount": "must be positive"}. */
    private Map<String, String> fieldErrors;

    private List<String> details;

    @Builder.Default
    private Instant timestamp = Instant.now();
}

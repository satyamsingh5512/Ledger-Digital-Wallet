package com.walletsys.exception;

import com.walletsys.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception-to-HTTP-response translation for the entire API surface.
 *
 * <p>Every handler here returns the same {@link ErrorResponse} envelope so clients only
 * ever need to parse one error shape, regardless of which layer (validation, security,
 * persistence, business rule) produced the failure. Internal exception details/stack
 * traces are never included in the response body — only a stable {@code errorCode} and
 * a human-readable {@code message} safe to show to API consumers. Full details are
 * logged server-side for operators.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ------------------------------------------------------------------
    // Application-specific exceptions (WalletSysException hierarchy)
    // ------------------------------------------------------------------

    @ExceptionHandler(WalletSysException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleWalletSysException(
            WalletSysException ex, HttpServletRequest request) {
        log.warn("{}: {}", ex.getErrorCode(), ex.getMessage());
        return buildResponse(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Spring Security
    // ------------------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to access this resource", request.getRequestURI(), null);
    }

    @ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
    public org.springframework.http.ResponseEntity<ErrorResponse> handleAuthenticationException(
            Exception ex, HttpServletRequest request) {
        log.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication failed", request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    /**
     * Surfaces after {@code @Retryable} on TransferAttemptExecutor/RefundAttemptExecutor
     * exhausts all attempts under sustained write contention on the same wallet — either
     * a clean optimistic-lock version mismatch ({@link OptimisticLockingFailureException})
     * or a database-level deadlock/lock-acquisition timeout under heavy concurrent load
     * on the same row ({@code CannotAcquireLockException}, also a
     * {@link ConcurrencyFailureException}). Both are retried identically by the attempt
     * executors and both land here if retries are exhausted. This is NOT a business-rule
     * violation — the operation itself is valid, it simply could not win the race for the
     * row in time. The client should retry the whole request; because every mutating
     * endpoint requires an Idempotency-Key, retrying is always safe and will not
     * double-apply the operation.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleConcurrencyFailure(
            ConcurrencyFailureException ex, HttpServletRequest request) {
        log.error("Concurrency retries exhausted on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION_RETRY_EXHAUSTED",
                "This resource is experiencing high concurrent write volume. Please retry your request "
                        + "(safe to repeat with the same Idempotency-Key).",
                request.getRequestURI(), null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.error("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The request could not be completed due to a data conflict (e.g. duplicate resource)",
                request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Bean validation on path/query params (@Validated on controller + @RequestParam/@PathVariable)
    // ------------------------------------------------------------------

    @ExceptionHandler(ConstraintViolationException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage()));

        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request parameters failed validation", request.getRequestURI(), fieldErrors);
    }

    // ------------------------------------------------------------------
    // Fallback for anything unmapped
    // ------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please try again later.", request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Overrides from ResponseEntityExceptionHandler (covers @Valid @RequestBody failures,
    // malformed JSON, unsupported HTTP method, missing headers, type mismatches, etc.)
    // ------------------------------------------------------------------

    @Override
    protected org.springframework.http.ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));

        String path = extractPath(request);
        ErrorResponse body = ErrorResponse.builder()
                .success(false)
                .errorCode("VALIDATION_FAILED")
                .message("Request body failed validation")
                .status(HttpStatus.BAD_REQUEST.value())
                .path(path)
                .fieldErrors(fieldErrors)
                .build();

        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @Override
    protected org.springframework.http.ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .success(false)
                .errorCode("MALFORMED_REQUEST_BODY")
                .message("The request body is missing or could not be parsed as valid JSON")
                .status(HttpStatus.BAD_REQUEST.value())
                .path(extractPath(request))
                .build();
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @Override
    protected org.springframework.http.ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .success(false)
                .errorCode("METHOD_NOT_ALLOWED")
                .message(ex.getMessage())
                .status(HttpStatus.METHOD_NOT_ALLOWED.value())
                .path(extractPath(request))
                .build();
        return org.springframework.http.ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleMissingRequestHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER",
                ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Parameter '" + ex.getName() + "' has an invalid value";
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message, request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private org.springframework.http.ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status, String errorCode, String message, String path, Map<String, String> fieldErrors) {
        ErrorResponse body = ErrorResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .message(message)
                .status(status.value())
                .path(path)
                .fieldErrors(fieldErrors)
                .build();
        return org.springframework.http.ResponseEntity.status(status).body(body);
    }

    private String extractPath(WebRequest request) {
        if (request instanceof org.springframework.web.context.request.ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return request.getDescription(false);
    }
}

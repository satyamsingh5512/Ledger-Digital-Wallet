package com.walletsys.controller;

import com.walletsys.dto.request.RefundRequest;
import com.walletsys.dto.response.ApiResponse;
import com.walletsys.dto.response.RefundResponse;
import com.walletsys.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
@Validated
@Tag(name = "Refunds", description = "Refunds against completed transactions")
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    @Operation(summary = "Refund a completed transaction (full or partial)",
            description = "Requires an Idempotency-Key header. Retrying with the same key returns the original result.")
    public ResponseEntity<ApiResponse<RefundResponse>> refund(
            @Valid @RequestBody RefundRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        RefundResponse response = refundService.refund(AuthenticatedUser.currentUserId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response, "Refund completed successfully"));
    }

    @GetMapping("/{refundId}")
    @Operation(summary = "Get a refund by id")
    public ResponseEntity<ApiResponse<RefundResponse>> getRefund(@PathVariable UUID refundId) {
        RefundResponse response = refundService.getRefund(refundId, AuthenticatedUser.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}

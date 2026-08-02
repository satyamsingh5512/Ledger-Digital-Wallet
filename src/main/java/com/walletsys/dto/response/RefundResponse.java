package com.walletsys.dto.response;

import com.walletsys.entity.enums.RefundStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {

    private UUID id;
    private UUID originalTransactionId;
    private UUID refundTransactionId;
    private BigDecimal amount;
    private String reason;
    private RefundStatus status;
    private Instant createdAt;
}

package com.walletsys.dto.response;

import com.walletsys.entity.enums.TransactionStatus;
import com.walletsys.entity.enums.TransactionType;
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
public class TransactionResponse {

    private UUID id;
    private String referenceId;
    private TransactionType type;
    private TransactionStatus status;
    private UUID sourceWalletId;
    private UUID destinationWalletId;
    private BigDecimal amount;
    private String currency;
    private String failureReason;
    private Instant createdAt;
}

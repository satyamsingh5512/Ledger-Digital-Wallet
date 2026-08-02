package com.walletsys.dto.response;

import com.walletsys.entity.enums.WalletStatus;
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
public class WalletResponse {

    private UUID id;
    private UUID userId;
    private String currency;
    private BigDecimal balance;
    private WalletStatus status;
    private Long version;
    private Instant createdAt;
}

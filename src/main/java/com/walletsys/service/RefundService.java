package com.walletsys.service;

import com.walletsys.dto.request.RefundRequest;
import com.walletsys.dto.response.RefundResponse;

import java.util.UUID;

public interface RefundService {

    RefundResponse refund(UUID initiatingUserId, RefundRequest request, String idempotencyKey);

    RefundResponse getRefund(UUID refundId, UUID requestingUserId);
}

package com.walletsys.mapper;

import com.walletsys.dto.response.RefundResponse;
import com.walletsys.entity.Refund;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RefundMapper {

    @Mapping(source = "originalTransaction.id", target = "originalTransactionId")
    @Mapping(source = "refundTransaction.id", target = "refundTransactionId")
    RefundResponse toResponse(Refund refund);
}

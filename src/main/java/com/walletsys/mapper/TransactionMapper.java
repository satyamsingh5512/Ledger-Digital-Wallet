package com.walletsys.mapper;

import com.walletsys.dto.response.TransactionResponse;
import com.walletsys.entity.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(source = "sourceWallet.id", target = "sourceWalletId")
    @Mapping(source = "destinationWallet.id", target = "destinationWalletId")
    TransactionResponse toResponse(Transaction transaction);
}

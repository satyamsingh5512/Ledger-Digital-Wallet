package com.walletsys.mapper;

import com.walletsys.dto.response.LedgerEntryResponse;
import com.walletsys.entity.LedgerEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface LedgerEntryMapper {

    @Mapping(source = "transaction.id", target = "transactionId")
    @Mapping(source = "wallet.id", target = "walletId")
    LedgerEntryResponse toResponse(LedgerEntry ledgerEntry);
}

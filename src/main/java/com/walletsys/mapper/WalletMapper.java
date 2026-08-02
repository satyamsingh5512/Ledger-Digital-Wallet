package com.walletsys.mapper;

import com.walletsys.dto.response.WalletResponse;
import com.walletsys.entity.Wallet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface WalletMapper {

    @Mapping(source = "user.id", target = "userId")
    WalletResponse toResponse(Wallet wallet);
}

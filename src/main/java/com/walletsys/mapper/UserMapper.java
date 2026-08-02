package com.walletsys.mapper;

import com.walletsys.dto.response.UserResponse;
import com.walletsys.entity.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);
}

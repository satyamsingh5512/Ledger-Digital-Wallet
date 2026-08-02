package com.walletsys.dto.response;

import com.walletsys.entity.enums.UserRole;
import com.walletsys.entity.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private UUID id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private UserStatus status;
    private UserRole role;
    private Instant createdAt;
}

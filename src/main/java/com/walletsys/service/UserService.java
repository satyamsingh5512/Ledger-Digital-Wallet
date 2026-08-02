package com.walletsys.service;

import com.walletsys.dto.request.LoginRequest;
import com.walletsys.dto.request.RegisterRequest;
import com.walletsys.dto.response.TokenResponse;
import com.walletsys.dto.response.UserResponse;

public interface UserService {

    UserResponse register(RegisterRequest request);

    TokenResponse login(LoginRequest request);

    TokenResponse refresh(String refreshToken);

    void logout(String refreshToken);

    UserResponse getCurrentUser(java.util.UUID userId);
}

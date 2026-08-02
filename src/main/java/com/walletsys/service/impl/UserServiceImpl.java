package com.walletsys.service.impl;

import com.walletsys.dto.request.LoginRequest;
import com.walletsys.dto.request.RegisterRequest;
import com.walletsys.dto.response.TokenResponse;
import com.walletsys.dto.response.UserResponse;
import com.walletsys.entity.RefreshToken;
import com.walletsys.entity.User;
import com.walletsys.entity.enums.UserRole;
import com.walletsys.entity.enums.UserStatus;
import com.walletsys.exception.DuplicateResourceException;
import com.walletsys.exception.InvalidCredentialsException;
import com.walletsys.exception.ResourceNotFoundException;
import com.walletsys.mapper.UserMapper;
import com.walletsys.repository.UserRepository;
import com.walletsys.security.RefreshTokenService;
import com.walletsys.security.UserPrincipal;
import com.walletsys.security.jwt.JwtService;
import com.walletsys.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new DuplicateResourceException("An account with email " + request.getEmail() + " already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();

        User saved = userRepository.save(user);
        log.info("Registered new user id={} email={}", saved.getId(), saved.getEmail());
        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        User user = userRepository.findByEmailIgnoreCase(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        return issueTokens(user);
    }

    @Override
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RefreshToken token = refreshTokenService.validateAndConsume(refreshToken);
        User user = token.getUser();
        return issueTokens(user);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        // Consuming (revoking) the presented token is sufficient for single-device logout;
        // a "logout everywhere" flow would call refreshTokenService.revokeAllForUser instead.
        refreshTokenService.validateAndConsume(refreshToken);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return userMapper.toResponse(user);
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = refreshTokenService.issue(user);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresInSeconds(jwtService.getAccessTokenExpirationSeconds())
                .build();
    }
}

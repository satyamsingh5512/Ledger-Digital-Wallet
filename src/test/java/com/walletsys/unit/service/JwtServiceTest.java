package com.walletsys.unit.service;

import com.walletsys.exception.InvalidTokenException;
import com.walletsys.security.jwt.JwtProperties;
import com.walletsys.security.jwt.JwtService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-key-for-jwt-signing-minimum-256-bits-long-please-ok");
        jwtProperties.setAccessTokenExpirationMs(900_000L);
        jwtProperties.setRefreshTokenExpirationMs(604_800_000L);
        jwtProperties.setIssuer("walletsys-test");

        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void generateAccessToken_producesTokenParsableBackToSameClaims() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "jane@example.com", "USER");

        Claims claims = jwtService.parseAndValidate(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("jane@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.getIssuer()).isEqualTo("walletsys-test");
    }

    @Test
    void extractUserId_returnsSameUuidEncodedAsSubject() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "jane@example.com", "USER");

        Claims claims = jwtService.parseAndValidate(token);
        assertThat(jwtService.extractUserId(claims)).isEqualTo(userId);
    }

    @Test
    void parseAndValidate_rejectsTamperedToken() {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "jane@example.com", "USER");
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThatThrownBy(() -> jwtService.parseAndValidate(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void parseAndValidate_rejectsTokenSignedWithDifferentSecret() {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("a-completely-different-secret-key-also-256-bits-minimum-long");
        otherProperties.setAccessTokenExpirationMs(900_000L);
        otherProperties.setIssuer("walletsys-test");
        JwtService otherJwtService = new JwtService(otherProperties);

        String token = otherJwtService.generateAccessToken(UUID.randomUUID(), "jane@example.com", "USER");

        assertThatThrownBy(() -> jwtService.parseAndValidate(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void parseAndValidate_rejectsTokenWithWrongIssuer() {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret(jwtProperties.getSecret());
        otherProperties.setAccessTokenExpirationMs(900_000L);
        otherProperties.setIssuer("some-other-issuer");
        JwtService otherJwtService = new JwtService(otherProperties);

        String token = otherJwtService.generateAccessToken(UUID.randomUUID(), "jane@example.com", "USER");

        assertThatThrownBy(() -> jwtService.parseAndValidate(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void getAccessTokenExpirationSeconds_convertsMillisCorrectly() {
        assertThat(jwtService.getAccessTokenExpirationSeconds()).isEqualTo(900L);
    }
}

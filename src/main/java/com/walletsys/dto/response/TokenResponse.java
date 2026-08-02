package com.walletsys.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Issued on login/refresh. Access tokens are short-lived and stateless (JWT); refresh
 *  tokens are opaque, longer-lived, and revocable server-side. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresInSeconds;
}

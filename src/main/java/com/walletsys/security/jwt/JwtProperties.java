package com.walletsys.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HMAC-SHA signing secret. Must be >= 256 bits. Rotate via secret manager in production. */
    private String secret;

    private long accessTokenExpirationMs;

    private long refreshTokenExpirationMs;

    private String issuer;
}

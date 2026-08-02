package com.walletsys.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a Bearer-JWT security scheme so Swagger UI's "Authorize" button attaches
 * {@code Authorization: Bearer <token>} to every try-it-out request, and documents the
 * API at a level appropriate for external integrators.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI walletSysOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("WalletSys — Distributed Ledger & Digital Wallet API")
                        .description("""
                                Production-grade digital wallet backend with an immutable double-entry ledger,
                                idempotent money movement (transfer/credit/debit/refund), optimistic-lock-based
                                double-spend prevention, Kafka event streaming, and Redis-backed caching/rate-limiting.

                                All mutating endpoints (transfer, credit, debit, refund) require an
                                `Idempotency-Key` header (a client-generated UUID). Retrying the same request
                                with the same key is always safe and returns the original result.
                                """)
                        .version("v1")
                        .contact(new Contact().name("WalletSys Engineering").email("engineering@walletsys.example"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}

package com.walletsys.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletsys.config.RateLimitProperties;
import com.walletsys.dto.response.ErrorResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Enforces a per-client token-bucket rate limit on every API request, backed by Redis
 * so the limit is shared and consistent across every application instance.
 *
 * <p><b>Client identity:</b> the authenticated user id (from the JWT, already populated
 * into the security context by {@link JwtAuthenticationFilter}, which runs before this
 * filter) is used as the rate-limit key when present — this is the correct identity for
 * a logged-in client regardless of which IP/NAT/proxy they're behind. For unauthenticated
 * requests (login, register, refresh) we fall back to the client IP, since that's the
 * only identity available before authentication succeeds; this is also what protects
 * the login endpoint itself from credential-stuffing / brute-force attempts.</p>
 *
 * <p>Configuration ({@code app.rate-limit.*}) is a single global limit applied
 * uniformly; a natural extension is per-endpoint or per-plan (free vs paid tier) limits,
 * which would just mean deriving a different {@link BucketConfiguration} per route
 * instead of the one shared configuration used here.</p>
 *
 * <p>Registered as a {@code @Component} so Spring wires its dependencies, but plugged
 * into the chain explicitly via {@code SecurityConfig.addFilterAfter} rather than being
 * auto-registered as a generic servlet filter — see {@code FilterRegistrationConfig}
 * which disables Spring Boot's default auto-registration for this bean to avoid it
 * running twice.</p>
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final ProxyManager<String> rateLimitProxyManager;
    private final RateLimitProperties rateLimitProperties;
    private final ObjectMapper objectMapper;

    private static final String[] EXCLUDED_PREFIXES = {
            "/actuator", "/swagger-ui", "/api-docs", "/v3/api-docs"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!rateLimitProperties.isEnabled() || isExcluded(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveClientKey(request);
        Bucket bucket = rateLimitProxyManager.builder().build(key, bucketConfigurationSupplier());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimitProperties.getCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(probe.getRemainingTokens(), 0)));

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
        } else {
            long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            writeTooManyRequests(response, request.getRequestURI());
        }
    }

    private Supplier<BucketConfiguration> bucketConfigurationSupplier() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rateLimitProperties.getCapacity())
                        .refillGreedy(rateLimitProperties.getRefillTokens(),
                                Duration.ofSeconds(rateLimitProperties.getRefillDurationSeconds()))
                        .build())
                .build();
    }

    private String resolveClientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof com.walletsys.security.UserPrincipal principal) {
            return "user:" + principal.getId();
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isExcluded(String uri) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void writeTooManyRequests(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse error = ErrorResponse.builder()
                .success(false)
                .errorCode("RATE_LIMIT_EXCEEDED")
                .message("Rate limit exceeded. Please slow down and retry later.")
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .path(path)
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}

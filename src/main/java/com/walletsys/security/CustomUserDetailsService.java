package com.walletsys.security;

import com.walletsys.cache.UserSessionCacheService;
import com.walletsys.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserSessionCacheService userSessionCacheService;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserPrincipal principal = userRepository.findByEmailIgnoreCase(email)
                .map(UserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user found with email: " + email));
        userSessionCacheService.put(principal.toCachedSession());
        return principal;
    }

    /**
     * Cache-aside lookup by user id — the hot path used by {@code JwtAuthenticationFilter}
     * on every authenticated request. A JWT's subject claim is the user id, so this
     * avoids the DB round trip email-lookup would otherwise require on every request.
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserById(UUID userId) throws UsernameNotFoundException {
        return userSessionCacheService.get(userId)
                .map(UserPrincipal::new)
                .map(UserDetails.class::cast)
                .orElseGet(() -> {
                    UserPrincipal principal = userRepository.findById(userId)
                            .map(UserPrincipal::new)
                            .orElseThrow(() -> new UsernameNotFoundException("No user found with id: " + userId));
                    userSessionCacheService.put(principal.toCachedSession());
                    return principal;
                });
    }
}

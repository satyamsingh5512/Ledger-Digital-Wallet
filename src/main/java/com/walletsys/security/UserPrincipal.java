package com.walletsys.security;

import com.walletsys.cache.CachedUserSession;
import com.walletsys.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security principal backed by our {@link User} entity. Kept intentionally thin —
 * it exposes only what the security filter chain and controllers need (id, email, role),
 * not the whole entity, so controllers don't accidentally reach into lazy-loaded
 * associations outside a transaction.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.enabled = user.getStatus() == com.walletsys.entity.enums.UserStatus.ACTIVE;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    public UserPrincipal(CachedUserSession session) {
        this.id = session.id();
        this.email = session.email();
        this.passwordHash = session.passwordHash();
        this.enabled = session.enabled();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + session.role()));
    }

    /** Projects this principal into its cache-friendly representation. */
    public CachedUserSession toCachedSession() {
        String role = authorities.iterator().next().getAuthority().replace("ROLE_", "");
        return new CachedUserSession(id, email, passwordHash, role, enabled);
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return enabled;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

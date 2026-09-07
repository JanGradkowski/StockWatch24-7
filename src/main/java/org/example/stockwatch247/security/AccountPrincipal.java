package org.example.stockwatch247.security;

import org.example.stockwatch247.model.User;
import org.springframework.security.core.authority.AuthorityUtils;

/** Binds a password authentication to the account version actually loaded by its provider. */
public final class AccountPrincipal extends org.springframework.security.core.userdetails.User {
    private final long securityVersion;
    public AccountPrincipal(User user, boolean enabled) {
        super(user.getEmail(), user.getPasswordHash(), enabled, true, true, true,
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        securityVersion = user.getSecurityVersion();
    }
    public long securityVersion() { return securityVersion; }
}

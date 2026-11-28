package de.tum.cit.aet.hephaestus.core.auth.oauth;

import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** A sign-in that names the login provider row version whose registration authenticated it. */
public class ProviderBoundToken extends OAuth2AuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final @Nullable String providerVersion;

    public ProviderBoundToken(
            OAuth2User principal,
            Collection<? extends GrantedAuthority> authorities,
            String registrationId,
            @Nullable String providerVersion) {
        super(principal, authorities, registrationId);
        this.providerVersion = providerVersion;
    }

    public @Nullable String providerVersion() {
        return providerVersion;
    }
}

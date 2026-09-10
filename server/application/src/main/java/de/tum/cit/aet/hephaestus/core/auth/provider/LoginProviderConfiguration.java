package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.OidcIssuerPolicy;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the login {@link LoginProviderClientRegistrationRepository} from the {@code login_provider}
 * store. Exposing it as the concrete type registers it under {@code ClientRegistrationRepository}
 * (Spring Security's {@code oauth2Login}), {@code IdentityProviderCatalog} (the discovery controller),
 * and {@code Iterable<ClientRegistration>}.
 */
@ConditionalOnServerRole
@Configuration
public class LoginProviderConfiguration {

    @Bean
    public LoginProviderClientRegistrationRepository loginProviderClientRegistrationRepository(
            LoginProviderRepository loginProviderRepository,
            AuthProperties authProperties,
            OutlineOriginPolicy outlineOriginPolicy,
            OidcIssuerPolicy oidcIssuerPolicy,
            OidcProviderDiscovery oidcProviderDiscovery) {
        return new LoginProviderClientRegistrationRepository(
                loginProviderRepository,
                authProperties.apiBasePath(),
                outlineOriginPolicy,
                oidcIssuerPolicy,
                oidcProviderDiscovery);
    }
}

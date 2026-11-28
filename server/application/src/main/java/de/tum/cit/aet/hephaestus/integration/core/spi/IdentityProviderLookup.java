package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Read-only identity provider metadata. */
public interface IdentityProviderLookup {
    Optional<Provider> findById(long providerId);

    Optional<Provider> findByOrigin(IdentityProviderType type, String origin);

    Optional<Long> findId(IdentityProviderType type, String serverUrl);

    record Provider(long id, IdentityProviderType type, String serverUrl) {
        /** Null for an OIDC issuer, which is no integration. */
        public @Nullable IntegrationKind kind() {
            return type.kind();
        }
    }
}

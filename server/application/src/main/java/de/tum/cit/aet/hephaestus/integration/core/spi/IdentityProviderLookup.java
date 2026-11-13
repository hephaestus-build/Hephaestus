package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import java.util.Optional;

/** Read-only identity provider metadata. */
public interface IdentityProviderLookup {
    Optional<Provider> findById(long providerId);

    Optional<Provider> findByOrigin(IdentityProviderType type, String origin);

    record Provider(long id, IdentityProviderType type, String serverUrl) {
        public IntegrationKind kind() {
            return type.kind();
        }
    }
}

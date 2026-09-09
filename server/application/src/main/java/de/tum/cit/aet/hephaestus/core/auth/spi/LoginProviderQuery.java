package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Optional;

/** Local, secret-free configuration for workspace-selected sign-in and account-linking flows. */
public interface LoginProviderQuery {
    Optional<Provider> findEnabled(String registrationId);

    /** The exact issuer is retained for OIDC; origins are normalized by the provider registry. */
    record Provider(String registrationId, String displayName, String type, String serverUrl) {}
}

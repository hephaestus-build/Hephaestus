package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.List;

/** Secret-free enabled account-linking providers. */
public interface LoginProviderQuery {
    List<Provider> enabledProviders();

    record Provider(String registrationId, String displayName, String type, String serverUrl) {}
}

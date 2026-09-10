package de.tum.cit.aet.hephaestus.core.auth.oauth;

import java.io.Serial;

/** Raised when a secondary identity provider is used as a primary sign-in method. */
public class LinkOnlyProviderLoginException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LinkOnlyProviderLoginException(String registrationId) {
        super("provider requires authenticated account linking: " + registrationId);
    }
}

package de.tum.cit.aet.hephaestus.core.auth.oauth;

import java.io.Serial;

/** Raised when a link-mode OAuth identity already belongs to another account. */
public class AccountLinkConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccountLinkConflictException(String registrationId, String subject, Long accountId) {
        super("auth.link: identity (provider=" + registrationId
                + ", subject="
                + subject
                + ") is already linked to a different accountId="
                + accountId);
    }
}

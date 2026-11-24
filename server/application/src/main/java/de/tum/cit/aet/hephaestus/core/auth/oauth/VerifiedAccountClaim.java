package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The exact identity proved by an OAuth callback and the account bound to its link intent. */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class VerifiedAccountClaim {
    private final long claimedAccountId;
    private final long unclaimedAccountId;
    private final PersonIdentity identity;
}

package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;

/** Supplies the proof that an OAuth callback would hand to the account lifecycle. */
public final class VerifiedAccountClaimFixture {

    private VerifiedAccountClaimFixture() {}

    public static VerifiedAccountClaim verifiedClaim(long claimed, long unclaimed, PersonIdentity identity) {
        return new VerifiedAccountClaim(claimed, unclaimed, identity);
    }
}

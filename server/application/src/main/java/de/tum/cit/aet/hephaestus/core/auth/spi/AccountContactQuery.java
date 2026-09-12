package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Optional;

/**
 * Cross-module read surface over an {@code Account}'s outbound contact address.
 *
 * <p>{@code primary_email} exists for outbound contact only — it never identifies an account at
 * login ({@code Account} javadoc). This port hands a consumer the address once it is verified, so a
 * notification is never sent to a mailbox the provider did not confirm belongs to the person: an
 * unverified address could tell a stranger that someone holds a Hephaestus account.
 *
 * <p>Implemented in {@code core.auth}; consumed by {@code notification}.
 */
public interface AccountContactQuery {

    /**
     * The account's provider-verified primary email address.
     *
     * @param accountId the Hephaestus-native account id
     * @return the address, or empty when the account has none, it is unverified, or the account does
     *     not exist (a purged account has no address either)
     */
    Optional<String> verifiedPrimaryEmail(long accountId);
}

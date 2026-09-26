package de.tum.cit.aet.hephaestus.core.auth.spi;

/**
 * Cross-module read query: does the account behind a federated identity hold a given role / feature
 * flag? Exposed so callers (e.g. the practices {@code UserRoleChecker} in the notification module) can
 * check roles without reaching into {@code core.auth}'s domain types.
 *
 * <p>Roles map 1:1 to {@code FeatureFlag} keys ({@code notification_access}, {@code admin}).
 * Resolution is {@code (gitProviderId, subject) → IdentityLink → Account → account_feature}
 * (appRole-based admin is resolved at JWT-mint time in {@code JwtPrincipalFactory}, not by this
 * query).
 *
 * <p>The identity is the stable {@code (gitProviderId, subject)} tuple — NOT a bare login — so the same
 * username on two SCM instances cannot cross-leak feature flags, and a provider-side rename does not
 * drop them. {@code subject} is the provider's numeric user id (the SCM {@code User.nativeId} as a
 * string).
 */
public interface AccountRoleQuery {
    /**
     * @param gitProviderId  the {@code git_provider} row id the identity belongs to
     * @param subject        the provider's stable numeric user id (string), == {@code IdentityLink.subject}
     * @param flag           the role / feature-flag key
     * @return true if the active account for that identity holds the flag; false otherwise (incl. errors)
     */
    boolean hasFeatureFlag(
            long gitProviderId,
            @org.jspecify.annotations.Nullable String subject,
            @org.jspecify.annotations.Nullable String flag);
}

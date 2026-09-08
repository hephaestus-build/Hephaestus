package de.tum.cit.aet.hephaestus.core.auth.spi;

import org.jspecify.annotations.Nullable;

/**
 * Narrow SPI that resolves an OAuth client {@code registrationId} to the persistent
 * {@code git_provider} row id, creating the row on first sight. Implemented in
 * {@code integration} (which owns the {@code IdentityProvider} aggregate); consumed by
 * {@code core.auth} during JIT account provisioning so the {@code IdentityLink} can be
 * keyed by {@code (git_provider_id, subject)} without {@code core.auth} importing the
 * integration entity (which would invert the bounded-context dependency direction).
 *
 * <p>{@code core.auth} owns the {@code login_provider} store and resolves a registration id to its
 * {@code (type, baseUrl)}; integration owns the {@code IdentityProvider} row and canonicalizes the base
 * URL to a server-url origin on upsert. Passing the pair (rather than the registration id) keeps
 * integration from reaching into {@code core.auth}'s store.
 */
public interface GitProviderRegistry {
    /**
     * Resolve (and create on first sight) the {@code git_provider} row id for a login provider.
     *
     * @param providerTypeName the git-provider type name — {@code GITHUB} or {@code GITLAB}
     * @param baseUrl          the provider's OAuth base URL (e.g. {@code https://github.com},
     *                         {@code https://gitlab.lrz.de}); canonicalized to a server-url origin
     * @return the persistent {@code git_provider} row id
     */
    long resolveProviderId(String providerTypeName, String baseUrl);

    /** Read an existing provider without creating reference data from a query. */
    java.util.Optional<Long> findProviderId(String providerTypeName, String baseUrl);

    /**
     * Resolve the provider <em>type</em> name (e.g. {@code GITHUB}, {@code GITLAB}) for a
     * {@code git_provider} row id. Used by read-side auth surfaces (profile, GDPR export)
     * to label an {@link de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink} without
     * navigating the integration-owned {@code IdentityProvider} entity.
     *
     * @param gitProviderId the {@code git_provider} row id, or {@code null}
     * @return the provider type name, or {@code "UNKNOWN"} when the id is {@code null} or no row exists
     */
    String providerTypeName(@Nullable Long gitProviderId);

    /**
     * Resolve the server-url origin (e.g. {@code https://github.com}, {@code https://gitlab.lrz.de}) for
     * a {@code git_provider} row id. Lets read-side auth surfaces tell <em>which instance</em> an
     * {@link de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink} belongs to — so a workspace-creation
     * gate can be instance-scoped, not merely type-scoped.
     *
     * @param gitProviderId the {@code git_provider} row id, or {@code null}
     * @return the server-url origin, or {@code null} when the id is {@code null} or no row exists
     */
    @Nullable
    String providerServerUrl(@Nullable Long gitProviderId);
}

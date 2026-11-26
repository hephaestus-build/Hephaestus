package de.tum.cit.aet.hephaestus.practices.adapter;

/**
 * The one predicate that finds a person's copied identity. It matches the row {@code copy} of
 * {@code review_item_copied_identity} to the requested identity {@code person}, an element of a JSON array of
 * {@code PersonCopyIdentity}. A subject is unique only within its provider, and {@code identity_provider} is unique on
 * {@code (type, server_url)}, so the provider type and origin bind it to exactly one provider. An Outline request
 * without a team covers the person in every team, as the receipt store reads it.
 *
 * <p>No other query compares a copied identity's subject. {@code NativeIdProviderBindingArchTest} pins this text.
 */
public final class CopiedIdentityMatch {
    // One literal: the gate reads each string literal by itself, so the whole predicate must be one.
    public static final String PERSON =
            "(copy.provider_type = person->>'providerType' AND copy.provider_origin = person->>'providerOrigin' AND copy.subject = person->>'subject' AND (copy.team_id IS NOT DISTINCT FROM person->>'teamId' OR (copy.provider_type = 'OUTLINE' AND person->>'teamId' IS NULL)))";

    private CopiedIdentityMatch() {}
}

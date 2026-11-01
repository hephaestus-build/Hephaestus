package de.tum.cit.aet.hephaestus.integration.scm.github.graphql;

/**
 * Classpath locations of the shared GitHub GraphQL fragment documents, named once so the runtime client,
 * the services that append a fragment to a query they assemble themselves, and the test-side validators
 * cannot drift apart. A fragment missing from one of those lists fails only at request time, against the
 * vendor.
 */
public final class GitHubGraphQlFragments {

    public static final String ACTOR_FRAGMENTS_RESOURCE = "graphql/github/fragments/ActorFragments.graphql";

    public static final String COMMIT_ENRICHMENT_FIELDS_RESOURCE =
            "graphql/github/fragments/CommitEnrichmentFields.graphql";

    public static final String COMMIT_ENRICHMENT_FIELDS_NAME = "CommitEnrichmentFields";

    private GitHubGraphQlFragments() {}
}

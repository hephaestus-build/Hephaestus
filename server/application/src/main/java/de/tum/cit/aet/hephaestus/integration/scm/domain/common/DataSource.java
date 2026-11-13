package de.tum.cit.aet.hephaestus.integration.scm.domain.common;

public enum DataSource {
    /** Data from scheduled GraphQL synchronization. */
    GRAPHQL_SYNC,
    /** Data from scheduled REST API synchronization. */
    REST_SYNC,
    /** Data from a webhook event through the inbox. */
    WEBHOOK,
}

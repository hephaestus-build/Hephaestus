package de.tum.cit.aet.hephaestus.integration.core.spi;

/** Positive, provider-native membership proof for admitting an existing organization member. Never writes upstream. */
public interface OrganizationMembershipProbe {
    IntegrationKind kind();

    Status check(Target target);

    record Target(Long workspaceId, String serverUrl, long organizationNativeId, long userNativeId) {}

    /** A hidden membership or unreadable provider response is not proof that the developer is a non-member. */
    enum Status {
        CONFIRMED,
        NOT_CONFIRMED,
        UNAVAILABLE
    }
}

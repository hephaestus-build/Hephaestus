package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab groups, mapping them to {@link Organization} entities.
 * <p>
 * Uses the JPA find-or-create pattern (consistent with {@code GitHubOrganizationProcessor})
 * to handle both create and update scenarios.
 * <p>
 * <b>Key mapping decisions:</b>
 * <ul>
 *   <li>{@code fullPath} → {@code login} (unique identifier, supports nested groups)</li>
 *   <li>Numeric ID extracted from GID via {@link GitLabSyncConstants#extractNumericId} → {@code nativeId} (provider-scoped)</li>
 *   <li>{@code webUrl} → {@code htmlUrl}</li>
 *   <li>{@code provider} = {@code GITLAB}</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabGroupProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabGroupProcessor.class);

    private final OrganizationRepository organizationRepository;
    private final SourceProviderNamespaces providerNamespaces;

    public GitLabGroupProcessor(
            OrganizationRepository organizationRepository, SourceProviderNamespaces providerNamespaces) {
        this.organizationRepository = organizationRepository;
        this.providerNamespaces = providerNamespaces;
    }

    /**
     * Processes a GitLab group GraphQL response into an Organization entity.
     * <p>
     * Uses find-or-create for consistent persistence with the GitHub processor.
     * If the group already exists, its mutable fields (login, name, avatar, URL) are updated.
     * IDs are provider-scoped native IDs, avoiding collision with other providers via the
     * (provider_id, native_id) unique constraint.
     *
     * @param group      the GitLab group GraphQL response
     * @param providerId the FK ID of the IdentityProvider entity
     * @return the persisted Organization entity, or null if the response is invalid
     */
    @Transactional
    @Nullable
    public Organization process(@Nullable GitLabGroupResponse group, Long providerId) {
        if (group == null || group.id() == null || group.fullPath() == null || group.webUrl() == null) {
            log.warn("Skipped group processing: reason=nullOrMissingFields");
            return null;
        }

        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(group.id());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped group processing: reason=invalidGlobalId, gid={}", group.id());
            return null;
        }

        Organization organization = organizationRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .orElseGet(() -> {
                    Organization org = new Organization();
                    org.setNativeId(nativeId);
                    org.setProvider(providerNamespaces.referenceForMirror(providerId));
                    org.setCreatedAt(Instant.now());
                    return org;
                });

        // Update mutable fields
        organization.setLogin(group.fullPath());
        organization.setName(group.name() != null ? group.name() : group.fullPath());
        organization.setAvatarUrl(group.avatarUrl());
        organization.setHtmlUrl(group.webUrl());
        organization.setLastSyncAt(Instant.now());

        Organization saved = organizationRepository.save(organization);
        if (organization.getId() == null) {
            log.debug("Created organization from GitLab group: nativeId={}, login={}", nativeId, group.fullPath());
        } else {
            log.debug("Updated organization from GitLab group: nativeId={}, login={}", nativeId, group.fullPath());
        }
        return saved;
    }
}

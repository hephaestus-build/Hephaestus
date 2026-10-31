package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub organizations.
 * <p>
 * This service handles the conversion of GitHubOrganizationDTO to Organization entities,
 * persists them, and manages organization lifecycle events.
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 * <li>Single processing path for all data sources (sync and webhooks)</li>
 * <li>Idempotent operations via upsert pattern</li>
 * <li>Works exclusively with DTOs for complete field coverage</li>
 * </ul>
 */
@Service
public class GitHubOrganizationProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrganizationProcessor.class);

    private final OrganizationRepository organizationRepository;
    private final IdentityProviderRepository gitProviderRepository;

    public GitHubOrganizationProcessor(
            OrganizationRepository organizationRepository, IdentityProviderRepository gitProviderRepository) {
        this.organizationRepository = organizationRepository;
        this.gitProviderRepository = gitProviderRepository;
    }

    /**
     * Process a GitHub organization DTO and persist it as an Organization entity.
     * Uses upsert pattern to handle both create and update scenarios.
     *
     * @param dto the GitHub organization DTO
     * @param providerId the FK ID of the IdentityProvider entity
     * @return the persisted Organization entity, or null if dto is invalid
     */
    @Transactional
    public @Nullable Organization process(GitHubOrganizationEventDTO.GitHubOrganizationDTO dto, Long providerId) {
        if (dto == null || dto.id() == null || providerId == null) {
            log.warn("Skipped organization processing: reason=nullOrMissingId");
            return null;
        }
        Long organizationId = Objects.requireNonNull(dto.id());

        Organization organization = organizationRepository
                .findByNativeIdAndProviderId(organizationId, providerId)
                .orElseGet(() -> {
                    Organization org = new Organization();
                    org.setNativeId(organizationId);
                    org.setProvider(gitProviderRepository.getReferenceById(providerId));
                    return org;
                });

        boolean isNew = organization.getId() == null;

        // Update fields
        if (dto.login() != null) {
            organization.setLogin(dto.login());
        }
        if (dto.avatarUrl() != null) {
            organization.setAvatarUrl(dto.avatarUrl());
        }
        // htmlUrl is required - use DTO value or construct from login
        if (dto.htmlUrl() != null) {
            organization.setHtmlUrl(dto.htmlUrl());
        } else if (organization.getHtmlUrl() == null && dto.login() != null) {
            // Construct htmlUrl from login as fallback
            organization.setHtmlUrl("https://github.com/" + dto.login());
            log.debug("Constructed htmlUrl from login for organization: orgLogin={}", dto.login());
        }
        if (dto.createdAt() != null) {
            organization.setCreatedAt(dto.createdAt());
        }
        if (dto.updatedAt() != null) {
            organization.setUpdatedAt(dto.updatedAt());
        }

        Organization saved = organizationRepository.save(organization);
        if (isNew) {
            log.debug("Created organization: orgId={}, orgLogin={}", saved.getNativeId(), saved.getLogin());
        } else {
            log.debug("Updated organization: orgId={}, orgLogin={}", saved.getNativeId(), saved.getLogin());
        }
        return saved;
    }

    /**
     * Rename an organization by its native ID and provider.
     *
     * @param nativeId the provider's native numeric ID of the organization
     * @param newLogin the new login name
     * @param providerId the FK ID of the IdentityProvider entity
     * @return the updated Organization entity, or null if not found
     */
    @Transactional
    public @Nullable Organization rename(Long nativeId, String newLogin, Long providerId) {
        if (nativeId == null || newLogin == null || providerId == null) {
            log.warn("Skipped organization rename: reason=nullParameter");
            return null;
        }

        return organizationRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .map(org -> {
                    String oldLogin = org.getLogin();
                    org.setLogin(newLogin);
                    Organization saved = organizationRepository.save(org);
                    log.info(
                            "Renamed organization: nativeId={}, oldLogin={}, newLogin={}",
                            nativeId,
                            oldLogin,
                            newLogin);
                    return saved;
                })
                .orElse(null);
    }

    /**
     * Delete an organization by its native ID and provider.
     * <p>
     * @param nativeId   the provider's native numeric ID of the organization
     * @param providerId the FK ID of the IdentityProvider entity
     */
    @Transactional
    public void delete(Long nativeId, Long providerId) {
        if (nativeId == null || providerId == null) {
            log.warn("Skipped organization delete: reason=nullParameter");
            return;
        }

        organizationRepository.findByNativeIdAndProviderId(nativeId, providerId).ifPresent(org -> {
            String orgLogin = org.getLogin();

            organizationRepository.delete(org);
            log.info("Deleted organization: nativeId={}, orgLogin={}", nativeId, sanitizeForLog(orgLogin));
        });
    }
}

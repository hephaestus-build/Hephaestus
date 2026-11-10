package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub organization webhook events.
 */
@Component
public class GitHubOrganizationMessageHandler extends AbstractIntegrationMessageHandler<GitHubOrganizationEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubOrganizationMessageHandler.class);

    private static final String GITHUB_SERVER_URL = "https://github.com";

    private final GitHubOrganizationProcessor organizationProcessor;
    private final GitHubUserProcessor userProcessor;
    private final IdentityProviderRepository gitProviderRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationMembershipListener membershipListener;

    GitHubOrganizationMessageHandler(
            GitHubOrganizationProcessor organizationProcessor,
            GitHubUserProcessor userProcessor,
            IdentityProviderRepository gitProviderRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            OrganizationMembershipListener membershipListener,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "organization." + GitHubEventType.ORGANIZATION.getValue(),
                GitHubOrganizationEventDTO.class,
                deserializer,
                transactionTemplate);
        this.organizationProcessor = organizationProcessor;
        this.userProcessor = userProcessor;
        this.gitProviderRepository = gitProviderRepository;
        this.membershipRepository = membershipRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.membershipListener = membershipListener;
    }

    @Override
    protected void handleEvent(GitHubOrganizationEventDTO event) {
        var orgDto = event.organization();

        if (orgDto == null) {
            log.warn("Received organization event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received organization event: action={}, orgId={}, orgLogin={}",
                event.action(),
                orgDto.id(),
                sanitizeForLog(orgDto.login()));

        // Resolve GitHub provider ID for user upsert
        Long providerId = Objects.requireNonNull(gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"))
                .getId());
        // The payload carries GitHub's ids. Memberships and their events use the stored organization's and person's
        // ids.
        Long nativeOrganizationId = Objects.requireNonNull(orgDto.id());

        switch (event.actionType()) {
            case GitHubEventAction.Organization.MEMBER_ADDED -> {
                if (event.membership() != null && event.membership().user() != null) {
                    var userDto = event.membership().user();
                    Organization organization = storedOrganization(nativeOrganizationId, providerId, event);
                    if (organization == null) return;
                    User user = Objects.requireNonNull(userProcessor.ensureExists(userDto, providerId));
                    Long organizationId = Objects.requireNonNull(organization.getId());
                    Long userId = Objects.requireNonNull(user.getId());
                    OrganizationMemberRole role = parseRole(event.membership().role());
                    membershipRepository.upsertMembership(organizationId, userId, role);
                    log.info(
                            "Added member to organization: orgId={}, orgLogin={}, userNativeId={}, role={}",
                            organizationId,
                            sanitizeForLog(orgDto.login()),
                            userDto.getDatabaseId(),
                            role);

                    // Notify listeners about membership change
                    membershipListener.onMemberAdded(new MembershipChangedEvent(
                            organizationId,
                            orgDto.login(),
                            userId,
                            userDto.login(),
                            event.membership().role()));
                }
            }
            case GitHubEventAction.Organization.MEMBER_REMOVED -> {
                if (event.membership() != null && event.membership().user() != null) {
                    var userDto = event.membership().user();
                    Organization organization = storedOrganization(nativeOrganizationId, providerId, event);
                    if (organization == null) return;
                    Long organizationId = Objects.requireNonNull(organization.getId());
                    var user = userRepository.findByNativeIdAndProviderId(
                            Objects.requireNonNull(userDto.id()), providerId);
                    if (user.isEmpty()) {
                        log.debug(
                                "Skipped member removal: reason=unknownUser, orgId={}, userNativeId={}",
                                organizationId,
                                userDto.getDatabaseId());
                        return;
                    }
                    Long userId = Objects.requireNonNull(user.get().getId());
                    membershipRepository.deleteByOrganizationIdAndUserIdIn(organizationId, List.of(userId));
                    log.info(
                            "Removed member from organization: orgId={}, orgLogin={}, userId={}",
                            organizationId,
                            sanitizeForLog(orgDto.login()),
                            userId);

                    // Notify listeners about membership change
                    membershipListener.onMemberRemoved(
                            new MembershipChangedEvent(organizationId, orgDto.login(), userId, userDto.login(), null));
                }
            }
            case GitHubEventAction.Organization.RENAMED ->
                organizationProcessor.rename(nativeOrganizationId, orgDto.login(), providerId);
            case GitHubEventAction.Organization.DELETED ->
                organizationProcessor.delete(nativeOrganizationId, providerId);
            default -> organizationProcessor.process(orgDto, providerId);
        }
    }

    /** The stored organization for GitHub's id, or null when the organization has not been synced yet. */
    private @Nullable Organization storedOrganization(
            Long nativeOrganizationId, Long providerId, GitHubOrganizationEventDTO event) {
        Organization organization = organizationRepository
                .findByNativeIdAndProviderId(nativeOrganizationId, providerId)
                .orElse(null);
        if (organization == null) {
            log.debug(
                    "Skipped organization member event: reason=unknownOrganization, action={}, nativeOrgId={}",
                    event.action(),
                    nativeOrganizationId);
        }
        return organization;
    }

    /**
     * Parses a role string from webhook events to the OrganizationMemberRole enum.
     *
     * @param roleString the role string from the webhook (e.g., "admin", "member")
     * @return the corresponding OrganizationMemberRole, defaulting to MEMBER if unknown
     */
    private OrganizationMemberRole parseRole(String roleString) {
        if (roleString == null) {
            return OrganizationMemberRole.MEMBER;
        }
        return switch (roleString.toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> OrganizationMemberRole.ADMIN;
            default -> OrganizationMemberRole.MEMBER;
        };
    }
}

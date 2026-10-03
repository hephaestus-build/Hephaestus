package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
    private final OrganizationMembershipListener membershipListener;

    GitHubOrganizationMessageHandler(
            GitHubOrganizationProcessor organizationProcessor,
            GitHubUserProcessor userProcessor,
            IdentityProviderRepository gitProviderRepository,
            OrganizationMembershipRepository membershipRepository,
            OrganizationMembershipListener membershipListener,
            NatsMessageDeserializer deserializer,
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
        Long organizationId = Objects.requireNonNull(orgDto.id());

        switch (event.actionType()) {
            case GitHubEventAction.Organization.MEMBER_ADDED -> {
                if (event.membership() != null && event.membership().user() != null) {
                    var userDto = event.membership().user();
                    User user = Objects.requireNonNull(userProcessor.ensureExists(userDto, providerId));
                    OrganizationMemberRole role = parseRole(event.membership().role());
                    membershipRepository.upsertMembership(organizationId, Objects.requireNonNull(user.getId()), role);
                    log.info(
                            "Added member to organization: orgId={}, orgLogin={}, userLogin={}, role={}",
                            orgDto.id(),
                            sanitizeForLog(orgDto.login()),
                            sanitizeForLog(userDto.login()),
                            role);

                    // Notify listeners about membership change
                    membershipListener.onMemberAdded(new MembershipChangedEvent(
                            organizationId,
                            orgDto.login(),
                            Objects.requireNonNull(userDto.id()),
                            userDto.login(),
                            event.membership().role()));
                }
            }
            case GitHubEventAction.Organization.MEMBER_REMOVED -> {
                if (event.membership() != null && event.membership().user() != null) {
                    var userDto = event.membership().user();
                    membershipRepository.deleteByOrganizationIdAndUserIdIn(
                            organizationId, List.of(Objects.requireNonNull(userDto.id())));
                    log.info(
                            "Removed member from organization: orgId={}, orgLogin={}, userLogin={}",
                            orgDto.id(),
                            sanitizeForLog(orgDto.login()),
                            sanitizeForLog(userDto.login()));

                    // Notify listeners about membership change
                    membershipListener.onMemberRemoved(new MembershipChangedEvent(
                            organizationId,
                            orgDto.login(),
                            Objects.requireNonNull(userDto.id()),
                            userDto.login(),
                            null));
                }
            }
            case GitHubEventAction.Organization.RENAMED ->
                organizationProcessor.rename(organizationId, orgDto.login(), providerId);
            case GitHubEventAction.Organization.DELETED -> organizationProcessor.delete(organizationId, providerId);
            default -> organizationProcessor.process(orgDto, providerId);
        }
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

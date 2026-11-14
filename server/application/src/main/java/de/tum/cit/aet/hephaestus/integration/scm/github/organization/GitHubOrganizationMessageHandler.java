package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubAccessReader;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.dto.GitHubOrganizationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
    private final IdentityProviderLookup identityProviders;
    private final OrganizationMembershipRepository membershipRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationMembershipListener membershipListener;
    private final ScopeIdResolver scopeIdResolver;
    private final GitHubAccessReader accessReader;

    GitHubOrganizationMessageHandler(
            GitHubOrganizationProcessor organizationProcessor,
            GitHubUserProcessor userProcessor,
            IdentityProviderLookup identityProviders,
            OrganizationMembershipRepository membershipRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            OrganizationMembershipListener membershipListener,
            ScopeIdResolver scopeIdResolver,
            GitHubAccessReader accessReader,
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
        this.identityProviders = identityProviders;
        this.membershipRepository = membershipRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.membershipListener = membershipListener;
        this.scopeIdResolver = scopeIdResolver;
        this.accessReader = accessReader;
    }

    /**
     * A member event names the person whose membership changed; what is stored is the membership GitHub reports now.
     * A delivery can arrive again after a later change, and the body's own membership would then restore it. The read
     * runs before the write transaction.
     */
    @Override
    protected void dispatchEvent(GitHubOrganizationEventDTO event) {
        if (!isMemberEvent(event)) {
            super.dispatchEvent(event);
            return;
        }
        Optional<CurrentMembership> current = currentMembership(event);
        if (current.isEmpty()) return;
        handleOnce(() -> applyMembership(event, current.get()));
    }

    @Override
    protected void handleEvent(GitHubOrganizationEventDTO event) {
        var orgDto = event.organization();

        if (orgDto == null) {
            log.warn("Received organization event with missing data: action={}", event.action());
            return;
        }

        log.debug("Received organization event: action={}, orgId={}", event.action(), orgDto.id());

        // Resolve GitHub provider ID for user upsert
        Long providerId = identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"));
        // The payload carries GitHub's ids. Memberships and their events use the stored organization's and person's
        // ids.
        Long nativeOrganizationId = Objects.requireNonNull(orgDto.id());

        if (isMemberEvent(event)) {
            throw new IllegalStateException("A member event reads GitHub before its write transaction; dispatch it");
        }
        switch (event.actionType()) {
            case GitHubEventAction.Organization.MEMBER_ADDED, GitHubEventAction.Organization.MEMBER_REMOVED ->
                log.warn("Received organization member event with missing membership: action={}", event.action());
            case GitHubEventAction.Organization.RENAMED ->
                organizationProcessor.rename(nativeOrganizationId, orgDto.login(), providerId);
            case GitHubEventAction.Organization.DELETED ->
                organizationProcessor.delete(nativeOrganizationId, providerId);
            default -> organizationProcessor.process(orgDto, providerId);
        }
    }

    private static boolean isMemberEvent(GitHubOrganizationEventDTO event) {
        return event.organization() != null
                && event.membership() != null
                && event.membership().user() != null
                && (event.actionType() == GitHubEventAction.Organization.MEMBER_ADDED
                        || event.actionType() == GitHubEventAction.Organization.MEMBER_REMOVED);
    }

    /** The person's membership as GitHub reports it now: their role, or none. */
    private record CurrentMembership(@Nullable String role) {}

    /**
     * Reads the membership without a transaction. Empty when no active workspace reads the organization, so there is
     * no token to read with and nothing to grant. A removal that cannot be read is applied, because removing access is
     * the safe direction; an addition that cannot be read fails, and the delivery is retried.
     */
    private Optional<CurrentMembership> currentMembership(GitHubOrganizationEventDTO event) {
        String organization = event.organization().login();
        Long userId = event.membership().user().id();
        Optional<Long> scopeId = scopeIdResolver.findScopeIdByOrgLogin(organization);
        if (userId == null || scopeId.isEmpty() || !accessReader.isReadable(scopeId.get())) {
            log.debug("Skipped organization member event: reason=noReadableWorkspace, action={}", event.action());
            return Optional.empty();
        }
        try {
            return Optional.of(new CurrentMembership(accessReader
                    .organizationRole(scopeId.get(), organization, userId)
                    .orElse(null)));
        } catch (RuntimeException readFailed) {
            if (event.actionType() != GitHubEventAction.Organization.MEMBER_REMOVED) throw readFailed;
            log.warn(
                    "Applied organization member removal without reading GitHub: error={}",
                    readFailed.getClass().getSimpleName());
            return Optional.of(new CurrentMembership(null));
        }
    }

    private void applyMembership(GitHubOrganizationEventDTO event, CurrentMembership current) {
        Long providerId = identityProviders
                .findId(IdentityProviderType.GITHUB, GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider not found for GitHub"));
        var orgDto = event.organization();
        var userDto = event.membership().user();
        Organization organization = storedOrganization(Objects.requireNonNull(orgDto.id()), providerId, event);
        if (organization == null) return;
        Long organizationId = Objects.requireNonNull(organization.getId());
        if (current.role() != null) {
            User user = Objects.requireNonNull(userProcessor.ensureExists(userDto, providerId));
            Long userId = Objects.requireNonNull(user.getId());
            OrganizationMemberRole role = parseRole(current.role());
            membershipRepository.upsertMembership(organizationId, userId, role);
            log.info(
                    "Stored organization membership: orgId={}, userNativeId={}, role={}",
                    organizationId,
                    userDto.getDatabaseId(),
                    role);
            membershipListener.onMemberAdded(new MembershipChangedEvent(
                    organizationId, orgDto.login(), userId, userDto.login(), current.role()));
            return;
        }
        var user = userRepository.findByNativeIdAndProviderId(Objects.requireNonNull(userDto.id()), providerId);
        if (user.isEmpty()) {
            log.debug(
                    "Skipped member removal: reason=unknownUser, orgId={}, userNativeId={}",
                    organizationId,
                    userDto.getDatabaseId());
            return;
        }
        Long userId = Objects.requireNonNull(user.get().getId());
        membershipRepository.deleteByOrganizationIdAndUserIdIn(organizationId, List.of(userId));
        log.info("Removed organization membership: orgId={}, userId={}", organizationId, userId);
        membershipListener.onMemberRemoved(
                new MembershipChangedEvent(organizationId, orgDto.login(), userId, userDto.login(), null));
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

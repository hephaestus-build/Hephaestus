package de.tum.cit.aet.hephaestus.integration.scm.gitlab.team;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.dto.GitLabSubgroupEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitLab subgroup webhook events for real-time team structure updates.
 * <p>
 * Processes {@code subgroup_*} events that are normalized to the "subgroup" event key by the webhook receiver.
 * <p>
 * Only a delivery admitted on its connection's route is handled: the team stored is the group GitLab reports.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabSubgroupMessageHandler extends AbstractIntegrationMessageHandler<GitLabSubgroupEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabSubgroupMessageHandler.class);

    private final GitLabTeamProcessor teamProcessor;
    private final IdentityProviderRepository gitProviderRepository;
    private final GitLabProperties gitLabProperties;
    private final GitLabRouteAdmission routeAdmission;
    private final TeamRepository teamRepository;

    GitLabSubgroupMessageHandler(
            GitLabTeamProcessor teamProcessor,
            IdentityProviderRepository gitProviderRepository,
            GitLabProperties gitLabProperties,
            GitLabRouteAdmission routeAdmission,
            TeamRepository teamRepository,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.SUBGROUP.getValue(),
                GitLabSubgroupEventDTO.class,
                deserializer,
                transactionTemplate);
        this.teamProcessor = teamProcessor;
        this.gitProviderRepository = gitProviderRepository;
        this.gitLabProperties = gitLabProperties;
        this.routeAdmission = routeAdmission;
        this.teamRepository = teamRepository;
    }

    @Override
    protected void handleEvent(GitLabSubgroupEventDTO event) {
        String safeFullPath = sanitizeForLog(event.fullPath());
        log.debug(
                "Received subgroup event: eventName={}, fullPath={}, groupId={}, parentGroupId={}",
                event.eventName(),
                safeFullPath,
                event.groupId(),
                event.parentGroupId());

        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElse(null);

        if (provider == null) {
            log.warn("IdentityProvider not found for GITLAB, skipping subgroup event");
            return;
        }

        Optional<GitLabRouteAdmission.AdmittedRoute> route = GitLabRouteAdmission.current();
        if (route.isEmpty()) {
            log.warn("Skipped subgroup event: reason=noAdmittedRoute, groupId={}", event.groupId());
            return;
        }
        applyReportedGroup(route.get(), event.groupId(), provider);
    }

    /**
     * On a connection route the event only says that a subgroup changed: the team stored is the group GitLab reports
     * under that id now, and only while it lies inside the connected group. A team row another workspace's group owns
     * is left to that workspace. Nothing is deleted here: GitLab not reporting a group to one connection does not prove
     * it is gone, so a deleted subgroup's team stays until the next full team sync, which removes teams GitLab no longer
     * lists.
     */
    private void applyReportedGroup(GitLabRouteAdmission.AdmittedRoute route, long groupId, IdentityProvider provider) {
        GitLabDescendantGroupResponse reported =
                GitLabRouteAdmission.reportedGroup().orElse(null);
        if (reported == null
                || groupId == route.groupId()
                || !route.contains(reported.fullPath())
                || !routeAdmission.holdActive(route)) {
            log.info("Skipped subgroup event: reason=notReportedInsideConnectedGroup, groupId={}", groupId);
            return;
        }
        Team existing = teamRepository
                .findByNativeIdAndProviderId(groupId, Objects.requireNonNull(provider.getId()))
                .orElse(null);
        if (existing != null && !route.groupPath().equalsIgnoreCase(existing.getOrganization())) {
            log.info("Skipped subgroup event: reason=teamOfAnotherGroup, groupId={}", groupId);
            return;
        }
        teamProcessor.process(reported, route.groupPath(), provider);
    }
}

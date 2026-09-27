package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import io.micrometer.common.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard-deletes a workspace's SCM mirror while preserving repositories another workspace monitors.
 * Derived workspace rows are erased first through {@link ScmMirrorErasedEvent}; operational audit
 * and instance-global identity rows are retained.
 */
@Component
@WorkspaceAgnostic("Erases instance-global SCM rows the workspace is the last tenant to monitor")
public class ScmWorkspaceContentEraser {

    private static final Logger log = LoggerFactory.getLogger(ScmWorkspaceContentEraser.class);

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceRepositoryMonitorService repositoryMonitorService;
    private final TeamRepository teamRepository;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final NatsConnectionProperties natsProperties;

    /** Absent under the webhook runtime role. */
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    private final ApplicationEventPublisher eventPublisher;

    public ScmWorkspaceContentEraser(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            WorkspaceRepositoryMonitorService repositoryMonitorService,
            TeamRepository teamRepository,
            OrganizationMembershipRepository organizationMembershipRepository,
            NatsConnectionProperties natsProperties,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            ApplicationEventPublisher eventPublisher) {
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryMonitorService = repositoryMonitorService;
        this.teamRepository = teamRepository;
        this.organizationMembershipRepository = organizationMembershipRepository;
        this.natsProperties = natsProperties;
        this.natsConsumerService = natsConsumerService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Irreversibly erases the workspace's SCM mirror. Idempotent: an already-erased (or
     * never-populated) workspace deletes 0 rows and completes normally.
     *
     * @param workspaceId the tenant whose SCM mirror is erased
     */
    @Transactional
    public void eraseWorkspaceScmMirror(long workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            log.debug("Skipped SCM mirror erase: reason=workspaceNotFound, workspaceId={}", workspaceId);
            return;
        }

        // Derived rows must be removed before the artifacts they reference.
        eventPublisher.publishEvent(new ScmMirrorErasedEvent(workspaceId));

        List<String> monitoredNames = repositoryToMonitorRepository.findByWorkspaceId(workspaceId).stream()
                .map(RepositoryToMonitor::getNameWithOwner)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        // Flush monitor removals before checking whether their repositories became orphaned.
        workspace.getRepositoriesToMonitor().clear();
        workspaceRepository.saveAndFlush(workspace);

        int erased = 0;
        int shared = 0;
        for (String nameWithOwner : monitoredNames) {
            long remaining = repositoryToMonitorRepository.countByNameWithOwner(nameWithOwner);
            repositoryMonitorService.deleteRepositoryIfOrphaned(nameWithOwner);
            if (remaining > 0) {
                shared++;
            } else {
                erased++;
            }
        }

        int teamsErased = eraseOrgTierIfLastWorkspace(workspace);

        if (natsProperties.enabled()) {
            natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspaceId));
        }

        log.info(
                "scm.audit: mirror erase — actor={}, workspaceId={}, erasedRepositories={}, sharedRepositoriesSkipped={}, erasedTeams={}",
                LoggingUtils.sanitizeForLog(SecurityUtils.getCurrentUserLogin().orElse("system")),
                workspaceId,
                erased,
                shared,
                teamsErased);
    }

    /** Erases org-tier mirrors only after the workspace releases its organization binding. */
    private int eraseOrgTierIfLastWorkspace(Workspace workspace) {
        Organization organization = workspace.getOrganization();
        if (organization == null || StringUtils.isBlank(organization.getLogin())) {
            return 0;
        }

        workspace.setOrganization(null);
        workspaceRepository.saveAndFlush(workspace);

        long otherTenants =
                workspaceRepository.countOtherActiveWorkspacesForOrganization(organization.getId(), workspace.getId());
        if (otherTenants > 0) {
            log.debug(
                    "Skipped org-tier SCM erase: reason=organizationStillBound, organizationId={}, otherWorkspaces={}",
                    organization.getId(),
                    otherTenants);
            return 0;
        }

        Long providerId = organization.getProvider() == null
                ? null
                : organization.getProvider().getId();
        if (providerId == null) {
            return 0;
        }

        Set<Team> teams = new LinkedHashSet<>(
                teamRepository.findByOrganizationIgnoreCaseAndProviderId(organization.getLogin(), providerId));
        teamRepository.deleteAll(new ArrayList<>(teams));
        organizationMembershipRepository.deleteByOrganizationId(organization.getId());
        return teams.size();
    }
}

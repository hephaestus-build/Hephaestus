package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
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
public class ScmWorkspaceContentEraser {

    private static final Logger log = LoggerFactory.getLogger(ScmWorkspaceContentEraser.class);

    private final WorkspaceOrganizationLookup organizations;
    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final WorkspaceRepositoryMonitorService repositoryMonitorService;
    private final TeamRepository teamRepository;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final NatsConnectionProperties natsProperties;

    // The webhook runtime does not provide a NATS consumer.
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    private final ApplicationEventPublisher eventPublisher;

    public ScmWorkspaceContentEraser(
            WorkspaceRepository workspaceRepository,
            WorkspaceOrganizationLookup organizations,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            WorkspaceRepositoryMonitorService repositoryMonitorService,
            TeamRepository teamRepository,
            OrganizationMembershipRepository organizationMembershipRepository,
            NatsConnectionProperties natsProperties,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            ApplicationEventPublisher eventPublisher) {
        this.workspaceRepository = workspaceRepository;
        this.organizations = organizations;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
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

        // Delete dependent rows before the work they reference.
        eventPublisher.publishEvent(new ScmMirrorErasedEvent(workspaceId));

        // Stopped GitLab projects have no monitor but stay indexed, so their sources are found through the items.
        Set<Long> sources = new LinkedHashSet<>(repositoryToMonitorRepository.findSourceRepositoryIds(workspaceId));
        sources.addAll(repositoryRepository.findIndexedRepositoryIds(workspaceId));
        List<Long> repositoryIds = List.copyOf(sources);

        // Flush monitor removals before checking whether their repositories became orphaned.
        workspace.getRepositoriesToMonitor().clear();
        workspaceRepository.saveAndFlush(workspace);
        // Unindexes this workspace's repository items; an item it kept would otherwise keep its source.
        eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(workspaceId, repositoryIds));

        int erased = 0;
        int shared = 0;
        for (Long repositoryId : repositoryIds) {
            repositoryMonitorService.deleteRepositoryIfOrphaned(repositoryId);
            if (repositoryRepository.existsById(repositoryId)) {
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
                "scm.audit: mirror erase — actor={}, workspaceId={}, erasedRepositories={},"
                        + " sharedRepositoriesSkipped={}, erasedTeams={}",
                SecurityUtils.getCurrentAccountId().map(id -> "account " + id).orElse("system"),
                workspaceId,
                erased,
                shared,
                teamsErased);
    }

    private int eraseOrgTierIfLastWorkspace(Workspace workspace) {
        Organization organization = organizations.find(workspace).orElse(null);
        if (organization == null || StringUtils.isBlank(organization.getLogin())) {
            return 0;
        }

        workspace.setOrganizationId(null);
        workspaceRepository.saveAndFlush(workspace);

        long otherTenants =
                workspaceRepository.countOtherActiveWorkspacesForOrganization(organization.getId(), workspace.getId());
        if (otherTenants > 0) {
            log.debug(
                    "Skipped org-tier SCM erase: reason=organizationStillBound, organizationId={},"
                            + " otherWorkspaces={}",
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

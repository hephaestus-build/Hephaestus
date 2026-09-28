package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitLabWorkspaceLinkService {

    private static final Logger log = LoggerFactory.getLogger(GitLabWorkspaceLinkService.class);

    private final WorkspaceRepository workspaceRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkspaceActorSelector actorSelector;
    private final IdentityProviderRepository identityProviderRepository;
    private final RepositoryToMonitorRepository monitorRepository;

    public GitLabWorkspaceLinkService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            WorkspaceActorSelector actorSelector,
            IdentityProviderRepository identityProviderRepository,
            RepositoryToMonitorRepository monitorRepository) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.actorSelector = actorSelector;
        this.identityProviderRepository = identityProviderRepository;
        this.monitorRepository = monitorRepository;
    }

    /**
     * Whether this scope may apply a destructive diff to a shared repository's rows: it monitors the repository,
     * which lies inside the group it may write per {@link #groupWriteProvider}. Callers check again inside the write,
     * since a listing can outlast a monitor, a connection or a link.
     */
    @Transactional(readOnly = true)
    public boolean mayWriteRepository(long scopeId, Repository repository) {
        String path = repository.getNameWithOwner();
        Long providerId = repository.getProvider().getId();
        if (path == null || providerId == null || !monitors(scopeId, repository, path)) {
            return false;
        }
        return workspaceRepository
                .findById(scopeId)
                .map(workspace -> {
                    Organization linked = workspace.getOrganization();
                    return linked != null ? linked.getLogin() : workspace.getAccountLogin();
                })
                .filter(groupPath -> path.length() > groupPath.length()
                        && path.charAt(groupPath.length()) == '/'
                        && path.regionMatches(true, 0, groupPath, 0, groupPath.length()))
                .flatMap(groupPath -> writeProvider(scopeId, groupPath))
                .map(IdentityProvider::getId)
                .filter(providerId::equals)
                .isPresent();
    }

    /** A monitor at the same path that records another project's native id is another project. */
    private boolean monitors(long scopeId, Repository repository, String path) {
        Long nativeId = repository.getNativeId();
        if (nativeId != null
                && !monitorRepository
                        .findByWorkspaceIdAndNativeId(scopeId, nativeId)
                        .isEmpty()) {
            return true;
        }
        return monitorRepository
                .findByWorkspaceIdAndNameWithOwner(scopeId, path)
                .filter(monitor ->
                        monitor.getNativeId() == null || monitor.getNativeId().equals(nativeId))
                .isPresent();
    }

    /**
     * The connected instance under which this scope writes the group at {@code groupPath}, while it may per
     * {@link #mayWriteGroup} or the group is not synced yet and is this unlinked workspace's own; empty otherwise.
     */
    @Transactional(readOnly = true)
    public Optional<IdentityProvider> groupWriteProvider(long scopeId, String groupPath) {
        return writeProvider(scopeId, groupPath);
    }

    private Optional<IdentityProvider> writeProvider(long scopeId, String groupPath) {
        return actorSelector
                .connectedProviderId(scopeId)
                .filter(providerId -> organizationRepository
                        .findByLoginIgnoreCaseAndProviderId(groupPath, providerId)
                        .map(organization -> ownsGroup(scopeId, organization))
                        .orElseGet(() -> workspaceRepository
                                .findById(scopeId)
                                .filter(workspace -> workspace.getOrganization() == null)
                                .map(workspace -> groupPath.equalsIgnoreCase(workspace.getAccountLogin()))
                                .orElse(false)))
                .flatMap(identityProviderRepository::findById);
    }

    /**
     * Whether a scope may write an organization's roster and teams: it is connected to the organization's instance
     * and linked to it, or unlinked at its path while no other workspace is. Another workspace on the same group reads
     * it with its own, possibly narrower, credentials, and what those miss proves nothing.
     */
    @Transactional(readOnly = true)
    public boolean mayWriteGroup(long scopeId, Organization organization) {
        return ownsGroup(scopeId, organization);
    }

    private boolean ownsGroup(long scopeId, Organization organization) {
        Long providerId = organization.getProvider().getId();
        if (providerId == null || !Optional.of(providerId).equals(actorSelector.connectedProviderId(scopeId))) {
            return false;
        }
        return workspaceRepository
                .findById(scopeId)
                .map(workspace -> {
                    Organization linked = workspace.getOrganization();
                    if (linked != null) {
                        return Objects.equals(linked.getId(), organization.getId());
                    }
                    return organization.getLogin().equalsIgnoreCase(workspace.getAccountLogin())
                            && !workspaceRepository.existsByOrganizationId(organization.getId());
                })
                .orElse(false);
    }

    @Transactional
    public void link(Workspace workspace) {
        if (workspace.getOrganization() != null
                || workspace.getAccountLogin() == null
                || workspace.getAccountLogin().isBlank()) {
            return;
        }
        // Another GitLab instance can host the same group path, so only the connected instance's row is linked.
        actorSelector
                .connectedProviderId(workspace.getId())
                .flatMap(providerId -> organizationRepository.findByLoginIgnoreCaseAndProviderId(
                        workspace.getAccountLogin(), providerId))
                .ifPresent(org -> {
                    if (workspaceRepository.existsByOrganizationId(org.getId())
                            && !workspaceRepository.existsByIdAndOrganizationId(workspace.getId(), org.getId())) {
                        log.warn(
                                "Organization already linked to another workspace: orgId={}, workspaceId={}",
                                org.getId(),
                                workspace.getId());
                        return;
                    }
                    workspaceRepository
                            .findById(workspace.getId())
                            .filter(current -> current.getOrganization() == null)
                            .ifPresent(current -> {
                                current.setOrganization(org);
                                workspaceRepository.save(current);
                                workspace.setOrganization(org);
                                log.info(
                                        "Linked organization to workspace: orgId={}, workspaceId={}",
                                        org.getId(),
                                        current.getId());
                            });
                });
    }
}

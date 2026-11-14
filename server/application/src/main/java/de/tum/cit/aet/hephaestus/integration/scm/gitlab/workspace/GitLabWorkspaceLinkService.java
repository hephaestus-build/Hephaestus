package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
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
    private final SourceProviderNamespaces providerNamespaces;
    private final RepositoryToMonitorRepository monitorRepository;

    public GitLabWorkspaceLinkService(
            WorkspaceRepository workspaceRepository,
            OrganizationRepository organizationRepository,
            WorkspaceActorSelector actorSelector,
            SourceProviderNamespaces providerNamespaces,
            RepositoryToMonitorRepository monitorRepository) {
        this.workspaceRepository = workspaceRepository;
        this.organizationRepository = organizationRepository;
        this.actorSelector = actorSelector;
        this.providerNamespaces = providerNamespaces;
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
        if (path == null || providerId == null || !monitors(scopeId, repository)) {
            return false;
        }
        return workspaceRepository
                .findById(scopeId)
                .map(workspace -> {
                    Organization linked = workspace.getOrganizationId() == null
                            ? null
                            : organizationRepository
                                    .findById(workspace.getOrganizationId())
                                    .orElse(null);
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

    /** A monitor identifies the project by its provider ID. */
    private boolean monitors(long scopeId, Repository repository) {
        return !monitorRepository
                .findByWorkspaceIdAndNativeId(scopeId, repository.getNativeId())
                .isEmpty();
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
                                .filter(workspace -> workspace.getOrganizationId() == null)
                                .map(workspace -> groupPath.equalsIgnoreCase(workspace.getAccountLogin()))
                                .orElse(false)))
                .flatMap(providerNamespaces::findForMirror);
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
                    Organization linked = workspace.getOrganizationId() == null
                            ? null
                            : organizationRepository
                                    .findById(workspace.getOrganizationId())
                                    .orElse(null);
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
        if (workspace.getOrganizationId() != null
                || workspace.getAccountLogin() == null
                || workspace.getAccountLogin().isBlank()) {
            return;
        }
        // Another GitLab instance can host the same group path, so only the connected instance's
        // row is linked.
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
                            .filter(current -> current.getOrganizationId() == null)
                            .ifPresent(current -> {
                                current.setOrganizationId(org.getId());
                                workspaceRepository.save(current);
                                workspace.setOrganizationId(org.getId());
                                log.info(
                                        "Linked organization to workspace: orgId={}, workspaceId={}",
                                        org.getId(),
                                        current.getId());
                            });
                });
    }
}

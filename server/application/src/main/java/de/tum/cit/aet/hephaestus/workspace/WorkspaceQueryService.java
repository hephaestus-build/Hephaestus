package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceProvidersDTO;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query service for workspace lookups.
 * <p>
 * This service provides query methods for finding and listing workspaces
 * without modifying state. All methods are read-only transactions.
 *
 * <p>Workspace-agnostic: This service manages workspaces themselves, operating
 * at the admin/registry level. It answers "which workspaces exist?" not
 * "what data is in a workspace?".
 */
@Service
@Transactional(readOnly = true)
@WorkspaceAgnostic("Manages workspaces themselves - operates at admin/registry level")
public class WorkspaceQueryService {

    private static final Comparator<Workspace> ACCESSIBLE_WORKSPACE_COMPARATOR = Comparator.comparing(
                    Workspace::getDisplayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Workspace::getWorkspaceSlug, String.CASE_INSENSITIVE_ORDER);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final CurrentAccountUsers currentAccountUsers;
    private final ConnectionService connectionService;

    /**
     * Per-kind availability ports — keyed for O(1) lookup when building the providers DTO.
     * Each vendor adapter contributes one impl; the query service stays kind-agnostic and
     * delegates the "is this provider exposable to the wizard?" decision back to the adapter.
     */
    private final Map<IntegrationKind, WorkspaceProviderAvailability> providerAvailability;

    private final WorkspaceProperties workspaceProperties;

    public WorkspaceQueryService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMembershipRepository workspaceMembershipRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            CurrentAccountUsers currentAccountUsers,
            ConnectionService connectionService,
            WorkspaceProperties workspaceProperties,
            List<WorkspaceProviderAvailability> providerAvailabilityList) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.currentAccountUsers = currentAccountUsers;
        this.connectionService = connectionService;
        this.workspaceProperties = workspaceProperties;
        Map<IntegrationKind, WorkspaceProviderAvailability> map = new EnumMap<>(IntegrationKind.class);
        for (WorkspaceProviderAvailability a : providerAvailabilityList) {
            map.put(a.kind(), a);
        }
        this.providerAvailability = map;
    }

    /**
     * Maps a {@link Workspace} entity to a {@link WorkspaceDTO}, hydrating the
     * integration-mode metadata from the active Connection(s). Exposed here so
     * controllers don't have to inject {@link ConnectionService} just to call the
     * DTO factory (keeps controllers under the 5-constructor-param arch rule).
     */
    public WorkspaceDTO toWorkspaceDTO(Workspace workspace) {
        return WorkspaceDTO.from(workspace, connectionService);
    }

    /**
     * Builds {@link WorkspaceListItemDTO}s for every workspace accessible to the
     * current authenticated user. During a user view, only the viewed workspace.
     */
    public List<WorkspaceListItemDTO> findAccessibleWorkspaceListItems() {
        var viewed = UserViewContextHolder.get();
        if (viewed != null) {
            return workspaceRepository.findById(viewed.workspaceId()).stream()
                    .map(workspace -> WorkspaceListItemDTO.from(workspace, connectionService))
                    .toList();
        }
        return findAccessibleWorkspaces().stream()
                .map(w -> WorkspaceListItemDTO.from(w, connectionService))
                .toList();
    }

    /**
     * Returns available workspace creation providers based on server configuration.
     *
     * <p>Resolves each provider's hint through the {@link WorkspaceProviderAvailability} SPI
     * — the query service itself is provider-agnostic. The DTO still carries kind-specific
     * fields because the wizard UI ultimately renders kind-specific flows; the boundary
     * between SPI and DTO is the {@code installationUrl} / {@code defaultServerUrl} string.
     */
    public WorkspaceProvidersDTO getAvailableProviders() {
        var github = providerAvailability.getOrDefault(IntegrationKind.GITHUB, null)
                        instanceof WorkspaceProviderAvailability ghAvail
                ? ghAvail.hintUrl()
                        .map(WorkspaceProvidersDTO.GitHubProviderDTO::new)
                        .orElse(null)
                : null;

        var gitlab = providerAvailability.getOrDefault(IntegrationKind.GITLAB, null)
                        instanceof WorkspaceProviderAvailability glAvail
                ? glAvail.hintUrl()
                        .map(url -> new WorkspaceProvidersDTO.GitLabProviderDTO(
                                ScmOrigin.of(url).orElse(url)))
                        .orElse(null)
                : null;

        return new WorkspaceProvidersDTO(github, gitlab, workspaceProperties.creationPolicy());
    }

    /**
     * Find a workspace by account login (case-insensitive).
     *
     * @param accountLogin the account login to search for
     * @return the workspace if found
     */
    public Optional<Workspace> findByAccountLogin(String accountLogin) {
        return workspaceRepository.findByAccountLoginIgnoreCase(accountLogin);
    }

    /**
     * List all active workspaces.
     *
     * @return list of workspaces that are active
     */
    public List<Workspace> findAll() {
        return workspaceRepository.findByStatus(Workspace.WorkspaceStatus.ACTIVE);
    }

    /**
     * Returns workspaces the current user can see: memberships + publicly viewable workspaces.
     * If no user is authenticated, only publicly viewable workspaces are returned.
     *
     * @return list of accessible workspaces for the current user
     */
    public List<Workspace> findAccessibleWorkspaces() {
        // Union across ALL of the account's linked identities (ADR 0017): a single account can mirror
        // several SCM users (one per provider login), so a member signed in via one provider must still
        // see workspaces they belong to under another. See CurrentAccountUsers.
        return findAccessibleWorkspaces(currentAccountUsers.resolve());
    }

    /**
     * Returns workspaces the given SCM users can see: the UNION of their memberships + publicly viewable
     * workspaces. An empty user set yields only the publicly viewable workspaces. Only ACTIVE workspaces
     * are included - SUSPENDED and PURGED workspaces are excluded. Passing several users is how a single
     * account's multiple linked identities are unioned.
     *
     * @param currentUsers the SCM users (the account's identity mirrors) to check accessibility for
     * @return list of accessible workspaces
     */
    public List<Workspace> findAccessibleWorkspaces(Collection<User> currentUsers) {
        // Always include public, active workspaces
        List<Workspace> publicWorkspaces =
                workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE);

        Set<Long> userIds = currentUsers.stream()
                .filter(u -> u != null && u.getId() != null)
                .map(User::getId)
                .collect(Collectors.toSet());

        if (userIds.isEmpty()) {
            return publicWorkspaces.stream()
                    .sorted(ACCESSIBLE_WORKSPACE_COMPARATOR)
                    .toList();
        }

        // Fetch memberships across every identity and load workspaces by ID
        var memberships = workspaceMembershipRepository.findByUser_IdIn(userIds);
        var workspaceIds = memberships.stream()
                .map(WorkspaceMembership::getWorkspace)
                .map(Workspace::getId)
                .distinct()
                .toList();

        List<Workspace> memberWorkspaces = workspaceIds.isEmpty()
                ? List.of()
                : workspaceRepository.findAllById(workspaceIds).stream()
                        .filter(w -> w.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                        .toList();

        // Merge and de-duplicate by ID to avoid duplicate entities with different instances
        return Stream.concat(publicWorkspaces.stream(), memberWorkspaces.stream())
                .collect(Collectors.toMap(
                        Workspace::getId, w -> w, (existing, replacement) -> existing, LinkedHashMap::new))
                .values()
                .stream()
                .sorted(ACCESSIBLE_WORKSPACE_COMPARATOR)
                .toList();
    }

    /**
     * Find a workspace by GitHub App installation ID. Joins through the GitHub
     * Connection's {@code instance_key} (the durable identity).
     */
    public Optional<Workspace> findByGitHubInstallationId(Long installationId) {
        return workspaceRepository.findByInstallationId(installationId);
    }

    /**
     * Find a workspace by its slug.
     *
     * @param slug the workspace slug
     * @return the workspace if found
     */
    public Optional<Workspace> findBySlug(String slug) {
        return workspaceRepository.findByWorkspaceSlug(slug);
    }

    /**
     * Resolve the workspace slug responsible for a given repository.
     * <p>
     * Priority:
     * <ol>
     *   <li>Explicit repository monitor (authoritative)</li>
     *   <li>Workspace account login matching repository owner (one-to-one enforced by business model)</li>
     * </ol>
     *
     * @param repository the repository to resolve the workspace slug for
     * @return the workspace slug if a unique mapping can be established, empty otherwise
     */
    public Optional<String> resolveWorkspaceSlug(Repository repository) {
        if (repository == null || isBlank(repository.getNameWithOwner())) {
            return Optional.empty();
        }

        var nameWithOwner = repository.getNameWithOwner();
        var monitor = repositoryToMonitorRepository.findByNameWithOwner(nameWithOwner);
        if (monitor.isPresent()) {
            Workspace workspace = monitor.get().getWorkspace();
            return workspace != null ? Optional.ofNullable(workspace.getWorkspaceSlug()) : Optional.empty();
        }

        // Fallback: org owner lookup (accountLogin is unique)
        String owner = nameWithOwner.contains("/") ? nameWithOwner.substring(0, nameWithOwner.indexOf("/")) : null;
        if (owner != null) {
            return workspaceRepository.findByAccountLoginIgnoreCase(owner).map(Workspace::getWorkspaceSlug);
        }

        return Optional.empty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

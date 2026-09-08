package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceProvidersDTO;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final WorkspaceAccountMembershipRepository accountMemberships;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
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
            WorkspaceAccountMembershipRepository accountMemberships,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            ConnectionService connectionService,
            WorkspaceProperties workspaceProperties,
            List<WorkspaceProviderAvailability> providerAvailabilityList) {
        this.workspaceRepository = workspaceRepository;
        this.accountMemberships = accountMemberships;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
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
     * current authenticated user.
     */
    public List<WorkspaceListItemDTO> findAccessibleWorkspaceListItems() {
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
                        .map(WorkspaceProvidersDTO.GitLabProviderDTO::new)
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
        List<Workspace> publicWorkspaces =
                workspaceRepository.findByStatusAndIsPubliclyViewableTrue(Workspace.WorkspaceStatus.ACTIVE);
        List<Workspace> memberWorkspaces =
                SecurityUtils.getCurrentAccountId()
                        .map(accountMemberships::findActiveByAccountId)
                        .orElseGet(List::of)
                        .stream()
                        .map(WorkspaceAccountMembership::getWorkspace)
                        .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
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

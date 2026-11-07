package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceProvidersDTO;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Workspace registry lookups span tenants; they must not read data inside a workspace.
@Service
@Transactional(readOnly = true)
@WorkspaceAgnostic("Manages workspaces themselves - operates at admin/registry level")
public class WorkspaceQueryService {

    private static final Comparator<Workspace> ACCESSIBLE_WORKSPACE_COMPARATOR = Comparator.comparing(
                    Workspace::getDisplayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Workspace::getWorkspaceSlug, String.CASE_INSENSITIVE_ORDER);

    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository workspaceMembershipRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final ConnectionService connectionService;
    private final PublicActivityPolicy publicActivityPolicy;

    private final Map<IntegrationKind, WorkspaceProviderAvailability> providerAvailability;

    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceSubdomainProperties subdomains;
    private final String webappUrl;

    public WorkspaceQueryService(
            WorkspaceRepository workspaceRepository,
            MembershipRepository workspaceMembershipRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            ConnectionService connectionService,
            PublicActivityPolicy publicActivityPolicy,
            WorkspaceProperties workspaceProperties,
            WorkspaceSubdomainProperties subdomains,
            @Value("${hephaestus.webapp.url}") String webappUrl,
            List<WorkspaceProviderAvailability> providerAvailabilityList) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.connectionService = connectionService;
        this.publicActivityPolicy = publicActivityPolicy;
        this.workspaceProperties = workspaceProperties;
        this.subdomains = subdomains;
        this.webappUrl = webappUrl;
        Map<IntegrationKind, WorkspaceProviderAvailability> map = new EnumMap<>(IntegrationKind.class);
        for (WorkspaceProviderAvailability a : providerAvailabilityList) {
            map.put(a.kind(), a);
        }
        this.providerAvailability = map;
    }

    public WorkspaceDTO toWorkspaceDTO(Workspace workspace) {
        return WorkspaceDTO.from(workspace, connectionService, workspaceAddress(workspace));
    }

    private String workspaceAddress(Workspace workspace) {
        return subdomains.address(workspace.getWorkspaceSlug(), webappUrl);
    }

    /**
     * Builds {@link WorkspaceListItemDTO}s for every workspace accessible to the current
     * authenticated user. During a user view, only the viewed workspace.
     */
    public List<WorkspaceListItemDTO> findAccessibleWorkspaceListItems() {
        boolean publicActivityAllowed = publicActivityPolicy.allowed();
        var viewed = UserViewContextHolder.get();
        if (viewed != null) {
            return workspaceRepository.findById(viewed.workspaceId()).stream()
                    .map(workspace -> WorkspaceListItemDTO.from(
                            workspace, connectionService, workspaceAddress(workspace), publicActivityAllowed))
                    .toList();
        }
        return findAccessibleWorkspaces().stream()
                .map(w -> WorkspaceListItemDTO.from(w, connectionService, workspaceAddress(w), publicActivityAllowed))
                .toList();
    }

    public @Nullable IdentityProviderType scmProviderType(Workspace workspace) {
        return connectionService
                .findActiveProviderKind(workspace.getId())
                .map(IdentityProviderType::from)
                .orElse(null);
    }

    /**
     * Returns available workspace creation providers based on server configuration.
     *
     * <p>Resolves each provider's hint through the {@link WorkspaceProviderAvailability} SPI — the
     * query service itself is provider-agnostic. The DTO still carries kind-specific fields because
     * the wizard UI ultimately renders kind-specific flows; the boundary between SPI and DTO is the
     * {@code installationUrl} / {@code defaultServerUrl} string.
     */
    public WorkspaceProvidersDTO getAvailableProviders() {
        WorkspaceProviderAvailability ghAvail = providerAvailability.get(IntegrationKind.GITHUB);
        var github = ghAvail == null
                ? null
                : ghAvail.hintUrl()
                        .map(WorkspaceProvidersDTO.GitHubProviderDTO::new)
                        .orElse(null);

        WorkspaceProviderAvailability glAvail = providerAvailability.get(IntegrationKind.GITLAB);
        var gitlab = glAvail == null
                ? null
                : glAvail.hintUrl()
                        .map(url -> new WorkspaceProvidersDTO.GitLabProviderDTO(
                                ScmOrigin.of(url).orElse(url)))
                        .orElse(null);

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

    public List<Long> findActiveIds() {
        return workspaceRepository.findIdsByStatus(Workspace.WorkspaceStatus.ACTIVE);
    }

    /**
     * Returns workspaces in which the current account has a membership. An unauthenticated caller
     * receives an empty list.
     *
     * @return list of accessible workspaces for the current user
     */
    public List<Workspace> findAccessibleWorkspaces() {
        var viewed = UserViewContextHolder.get();
        Long accountId =
                viewed == null ? SecurityUtils.getCurrentAccountId().orElse(null) : Long.valueOf(viewed.accountId());
        if (accountId == null) return List.of();
        var memberships = workspaceMembershipRepository.findById_AccountId(accountId).stream()
                .filter(member -> member.getLeftAt() == null)
                .toList();
        var workspaceIds = memberships.stream()
                .map(Membership::getWorkspace)
                .map(Workspace::getId)
                .distinct()
                .toList();

        List<Workspace> memberWorkspaces = workspaceIds.isEmpty()
                ? List.of()
                : workspaceRepository.findAllById(workspaceIds).stream()
                        .filter(w -> w.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                        .toList();

        return memberWorkspaces.stream().sorted(ACCESSIBLE_WORKSPACE_COMPARATOR).toList();
    }

    /**
     * Find a workspace by GitHub App installation ID. Joins through the GitHub Connection's {@code
     * instance_key} (the durable identity).
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
     *
     * <p>Priority:
     *
     * <ol>
     *   <li>Explicit repository monitor (authoritative)
     *   <li>Workspace account login matching repository owner (one-to-one enforced by business
     *       model)
     * </ol>
     *
     * @param nameWithOwner the repository, as owner/name, to resolve the workspace slug for
     * @return the workspace slug if a unique mapping can be established, empty otherwise
     */
    public Optional<String> resolveWorkspaceSlug(String nameWithOwner) {
        if (isBlank(nameWithOwner)) {
            return Optional.empty();
        }

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

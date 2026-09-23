package de.tum.cit.aet.hephaestus.workspace;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a {@link Workspace} from repository identifiers using direct repository access.
 * <p>
 * Two-step resolution strategy:
 * <ol>
 *   <li><strong>Authoritative</strong>: looks up the explicit monitor configuration
 *       via {@link RepositoryToMonitorRepository}</li>
 *   <li><strong>Heuristic</strong>: infers the workspace from the repository owner login
 *       via {@link WorkspaceRepository}</li>
 * </ol>
 * <p>
 * <strong>Important:</strong> This service must depend ONLY on repositories,
 * not on other {@code @Service} beans, to avoid circular dependencies with
 * {@link WorkspaceService} and scheduling components.
 */
@Service
public class WorkspaceResolver {

    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final WorkspaceRepository workspaceRepository;

    public WorkspaceResolver(
            RepositoryToMonitorRepository repositoryToMonitorRepository, WorkspaceRepository workspaceRepository) {
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Resolves the first workspace for callers that can use only one workspace. Issue event
     * processing uses {@link #resolveAllForRepository(String)} instead.
     *
     * <p>The workspace comes back initialized, not as a proxy. The monitor's association is lazy, so
     * handing back {@code monitor.getWorkspace()} used to return something that reads fine inside the
     * caller's session and throws {@code LazyInitializationException} outside one — which made this
     * method's answer depend on the caller's transaction rather than on the repository it was asked
     * about. Anyone who resolves a workspace goes on to read one, so fetching it is the contract.
     *
     * @param nameWithOwner the full repository name (e.g., "ls1intum/Hephaestus"), may be null
     * @return the workspace if found, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<Workspace> resolveForRepository(@Nullable String nameWithOwner) {
        return resolveAll(nameWithOwner).stream().findFirst();
    }

    /** A shared repository can have one monitor in each of several workspaces. */
    @Transactional(readOnly = true)
    public List<Workspace> resolveAllForRepository(@Nullable String nameWithOwner) {
        return resolveAll(nameWithOwner);
    }

    private List<Workspace> resolveAll(@Nullable String nameWithOwner) {
        if (nameWithOwner == null) {
            return List.of();
        }
        var monitors = repositoryToMonitorRepository.findAllWithWorkspaceByNameWithOwner(nameWithOwner);
        if (!monitors.isEmpty()) {
            return monitors.stream()
                    .map(monitor -> Objects.requireNonNull(monitor.getWorkspace()))
                    .toList();
        }
        String owner = nameWithOwner.contains("/") ? nameWithOwner.substring(0, nameWithOwner.indexOf("/")) : null;
        return owner == null || owner.isEmpty()
                ? List.of()
                : workspaceRepository.findByAccountLoginIgnoreCase(owner).stream()
                        .toList();
    }
}

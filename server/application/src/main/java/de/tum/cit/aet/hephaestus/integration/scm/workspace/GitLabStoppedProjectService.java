package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemQueryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A GitLab Stop keeps the repository item and the work the workspace indexed under it. This service lists those
 * stopped projects and removes the kept work of one on request. A removal names the receipt of the Stop it answers
 * and is checked under the connection lock that Stop and Add take, so a request prepared before a later Add and Stop
 * refuses instead of acting on the newer Stop. Practice reviews, results and feedback about the work keep their own
 * retention.
 */
@Service
@RequiredArgsConstructor
public class GitLabStoppedProjectService {

    private static final Logger log = LoggerFactory.getLogger(GitLabStoppedProjectService.class);

    private final ConnectionService connectionService;
    private final ConnectionCommands connectionCommands;
    private final ConnectionQueries connectionQueries;
    private final ScmItemQueryRepository items;
    private final WorkspaceRepositoryMonitorService repositoryMonitorService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<GitLabStoppedProjectDTO> getStoppedProjects(long workspaceId) {
        return connectionQueries
                .findActiveOrSuspendedConfig(workspaceId, IntegrationKind.GITLAB)
                .map(connection -> stoppedProjects(workspaceId, connection).stream()
                        .map(project -> new GitLabStoppedProjectDTO(
                                String.valueOf(project.itemId()), project.nameWithOwner(), project.receipt()))
                        .toList())
                .orElse(List.of());
    }

    @Transactional
    public void removeStoppedProject(long workspaceId, long repositoryItemId, UUID stopReceipt, long actorAccountId) {
        // Taking the lock first: Stop rotates and Add clears the receipt while they hold it.
        Connection connection = connectionService
                .updateGitLabProjectExclusions(workspaceId, exclusions -> exclusions)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This workspace has no GitLab connection to remove project work from."));
        // The lock refreshed the connection. A paused one keeps its stops, but nothing is removed until it resumes.
        if (connection.getState() != IntegrationState.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Resume the GitLab connection before you remove project work.");
        }
        StoppedProject project = stoppedProjects(workspaceId, connection.getConfig()).stream()
                .filter(candidate -> candidate.itemId() == repositoryItemId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This workspace keeps no work from a stopped project with this ID."));
        if (!project.receipt().equals(stopReceipt)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This project was added or stopped again after the list was loaded. Reload and try again.");
        }
        connectionService.updateGitLabProjectExclusions(
                workspaceId, exclusions -> exclusions.withoutStopReceipt(project.nativeId()));
        // The workspace no longer monitors the source, so this unindexes its repository item.
        eventPublisher.publishEvent(new RepositoryMonitoringChangedEvent(workspaceId, List.of(project.repositoryId())));
        repositoryMonitorService.deleteRepositoryIfOrphaned(project.repositoryId());
        connectionCommands.recordAdministrativeChange(
                connection,
                "STOPPED_PROJECT_WORK_REMOVED",
                actorAccountId,
                stopReceipt.toString(),
                project.nameWithOwner());
        log.info(
                "Removed kept work of stopped GitLab project: repositoryId={}, workspaceId={}",
                project.repositoryId(),
                workspaceId);
    }

    /** Kept roots of this connection's GitLab origin whose project the connection excludes under a receipt. */
    private List<StoppedProject> stoppedProjects(long workspaceId, ConnectionConfig supplied) {
        if (!(supplied instanceof ConnectionConfig.GitLabConfig config)) return List.of();
        Optional<String> origin = PersonSourceNamespace.from(
                        IntegrationKind.GITLAB.name(), SourceProviderNamespaces.configuredUrl(config))
                .flatMap(namespace -> ScmOrigin.of(namespace.serverUrl()));
        if (origin.isEmpty()) return List.of();
        var exclusions = config.projectExclusions();
        return items.unmonitored(workspaceId, ScmItemEvents.REPOSITORY.value(), IntegrationKind.GITLAB.name()).stream()
                .filter(row -> origin.equals(ScmOrigin.of(row.getServerUrl())))
                .flatMap(row -> exclusions.stopReceipt(row.getNativeId()).stream()
                        .map(receipt -> new StoppedProject(
                                row.getItemId(),
                                row.getRepositoryId(),
                                row.getNativeId(),
                                row.getNameWithOwner(),
                                receipt)))
                .toList();
    }

    private record StoppedProject(long itemId, long repositoryId, long nativeId, String nameWithOwner, UUID receipt) {}
}

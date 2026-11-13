package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.micrometer.common.util.StringUtils;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads and changes whether a GitLab workspace follows every project of its group or only the ones it monitors. */
@Service
@WorkspaceAgnostic("Manages workspace-repository mapping configuration")
public class GitLabRepositorySelectionService {

    private static final Logger log = LoggerFactory.getLogger(GitLabRepositorySelectionService.class);

    private final WorkspaceRepository workspaceRepository;
    private final ConnectionQueries connectionQueries;
    private final ConnectionCommands connectionCommands;
    private final TransactionTemplate transactionTemplate;

    public GitLabRepositorySelectionService(
            WorkspaceRepository workspaceRepository,
            ConnectionQueries connectionQueries,
            ConnectionCommands connectionCommands,
            TransactionTemplate transactionTemplate) {
        this.workspaceRepository = workspaceRepository;
        this.connectionQueries = connectionQueries;
        this.connectionCommands = connectionCommands;
        this.transactionTemplate = transactionTemplate;
    }

    /** Whether the workspace's GitLab connection follows every project of its group, as stored now. */
    @Transactional(readOnly = true)
    public RepositorySelection getGitLabRepositorySelection(WorkspaceContext workspaceContext) {
        String slug = requireSlug(workspaceContext);
        Long workspaceId = requireWorkspace(slug).getId();
        if (connectionQueries
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .filter(connection -> connection.config() instanceof ConnectionConfig.GitLabConfig)
                .isEmpty()) {
            throw gitLabNotActive(slug);
        }
        return storedSelection(workspaceId);
    }

    /** Changes the selection under the same lifecycle lock as Add, Stop, and discovery. */
    public RepositorySelection updateGitLabRepositorySelection(
            WorkspaceContext workspaceContext, RepositorySelection requested, long actorAccountId) {
        String slug = requireSlug(workspaceContext);
        Long workspaceId = requireWorkspace(slug).getId();
        RepositorySelection selection = transactionTemplate.execute(status -> {
            Workspace workspace = workspaceRepository
                    .findByIdForUpdate(workspaceId)
                    .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
            Connection current = connectionCommands
                    .holdActiveConnection(workspaceId, IntegrationKind.GITLAB)
                    .filter(connection -> connection.getConfig() instanceof ConnectionConfig.GitLabConfig)
                    .orElseThrow(() -> gitLabNotActive(slug));
            RepositorySelection stored = storedSelection(workspaceId);
            if (stored == requested) return stored;
            workspace.setRepositorySelection(requested);
            workspaceRepository.saveAndFlush(workspace);
            recordChange(current, stored, requested, actorAccountId);
            log.info(
                    "Changed GitLab project selection: workspaceId={}, from={}, to={}", workspaceId, stored, requested);
            return requested;
        });
        return Objects.requireNonNull(selection);
    }

    /** A workspace without a stored choice follows every project, as discovery and admission already treat it. */
    RepositorySelection storedSelection(long workspaceId) {
        return workspaceRepository
                        .findMonitorPolicy(workspaceId)
                        .filter(policy -> policy.getRepositorySelection() == RepositorySelection.SELECTED)
                        .isPresent()
                ? RepositorySelection.SELECTED
                : RepositorySelection.ALL;
    }

    /** Written in the caller's transaction, under the connection's lifecycle lock, beside the change it records. */
    private void recordChange(
            Connection connection, RepositorySelection from, RepositorySelection to, long actorAccountId) {
        connectionCommands.recordAdministrativeChange(
                connection,
                "PROJECT_SELECTION_CHANGED",
                actorAccountId,
                // The idempotency index treats an absent correlation id as one value, so each change carries its own.
                UUID.randomUUID().toString(),
                from + " → " + to);
    }

    private Workspace requireWorkspace(String slug) {
        if (StringUtils.isBlank(slug)) {
            throw new IllegalArgumentException("Workspace slug must not be blank.");
        }
        return workspaceRepository
                .findByWorkspaceSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", slug));
    }

    private static String requireSlug(WorkspaceContext workspaceContext) {
        Objects.requireNonNull(workspaceContext, "WorkspaceContext must not be null");
        String slug = workspaceContext.slug();
        if (StringUtils.isBlank(slug)) {
            throw new IllegalArgumentException("Workspace context slug must not be blank.");
        }
        return slug;
    }

    private static ResponseStatusException gitLabNotActive(String slug) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                LoggingUtils.sanitizeForLog("The workspace “" + slug + "” has no active GitLab connection."
                        + " Only a connected GitLab workspace chooses which projects it follows."));
    }
}

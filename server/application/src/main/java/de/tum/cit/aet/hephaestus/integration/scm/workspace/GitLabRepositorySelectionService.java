package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAuditRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.micrometer.common.util.StringUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private static final Logger log = LoggerFactory.getLogger(GitLabRepositorySelectionService.class);

    private final WorkspaceRepository workspaceRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionAuditRepository auditRepository;
    private final TransactionTemplate transactionTemplate;

    public GitLabRepositorySelectionService(
            WorkspaceRepository workspaceRepository,
            ConnectionRepository connectionRepository,
            ConnectionAuditRepository auditRepository,
            TransactionTemplate transactionTemplate) {
        this.workspaceRepository = workspaceRepository;
        this.connectionRepository = connectionRepository;
        this.auditRepository = auditRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /** Whether the workspace's GitLab connection follows every project of its group, as stored now. */
    @Transactional(readOnly = true)
    public RepositorySelection getGitLabRepositorySelection(WorkspaceContext workspaceContext) {
        String slug = requireSlug(workspaceContext);
        Long workspaceId = requireWorkspace(slug).getId();
        if (connectionRepository
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .filter(connection -> connection.getConfig() instanceof ConnectionConfig.GitLabConfig)
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
            if (connectionRepository
                    .lockActiveConfig(workspaceId, IntegrationKind.GITLAB)
                    .filter(ConnectionConfig.GitLabConfig.class::isInstance)
                    .isEmpty()) {
                throw gitLabNotActive(slug);
            }
            Connection current = connectionRepository
                    .findActive(workspaceId, IntegrationKind.GITLAB)
                    .orElseThrow(() -> gitLabNotActive(slug));
            EntityManager em = Objects.requireNonNull(entityManager);
            em.refresh(current);
            RepositorySelection stored = storedSelection(workspaceId);
            if (stored == requested) return stored;
            Workspace workspace = workspaceRepository
                    .findById(workspaceId)
                    .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
            em.refresh(workspace);
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
        ConnectionAudit audit = new ConnectionAudit(
                connection,
                "PROJECT_SELECTION_CHANGED",
                connection.getState(),
                connection.getState(),
                "ADMIN",
                null,
                // The idempotency index treats an absent correlation id as one value, so each change carries its own.
                UUID.randomUUID().toString(),
                from + " → " + to);
        audit.setActorAccountId(actorAccountId);
        auditRepository.save(audit);
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

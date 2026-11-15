package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SystemSecretOwner;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * CRUD (plus "test connection") for a workspace's own "bring your own" LLM connection: tenant-scoped,
 * workspace-admin-owned, self-funded.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceLlmConnectionService implements SystemSecretOwner {

    private final WorkspaceLlmConnectionRepository connectionRepository;
    private final WorkspaceLlmModelRepository modelRepository;
    private final WorkspaceSummaryQuery workspaceSummaries;
    private final EgressPolicy egressPolicy;
    private final InstanceLlmSettingsService instanceLlmSettingsService;
    private final LlmModelLimitDiscovery limitDiscovery;
    private final ConfigAuditPort configAudit;
    private final SystemSecrets secrets;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<WorkspaceLlmConnection> list(WorkspaceContext workspaceContext) {
        return connectionRepository.findByWorkspaceId(workspaceContext.id());
    }

    private WorkspaceLlmConnection load(WorkspaceContext workspaceContext, Long id) {
        return connectionRepository
                .findByIdAndWorkspaceId(id, workspaceContext.id())
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceLlmConnection", id));
    }

    @Transactional
    public WorkspaceLlmConnection create(
            WorkspaceContext workspaceContext, CreateWorkspaceLlmConnectionRequestDTO request) {
        requireByoEnabled();
        Long workspaceId = workspaceContext.id();
        String slug = connectionSlug(workspaceId, request.slug(), request.displayName());
        if (StringUtils.hasText(request.slug())
                && connectionRepository
                        .findByWorkspaceIdAndSlug(workspaceId, slug)
                        .isPresent()) {
            throw new LlmConnectionSlugConflictException(slug);
        }
        egressPolicy.validate(request.baseUrl());

        if (workspaceSummaries.findById(workspaceId).isEmpty())
            throw new EntityNotFoundException("Workspace", workspaceContext.slug());

        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setWorkspace(Objects.requireNonNull(entityManager).getReference(Workspace.class, workspaceId));
        connection.setSlug(slug);
        connection.setDisplayName(request.displayName());
        connection.setConnectionPlatform(request.connectionPlatform());
        connection.setBaseUrl(request.baseUrl().trim());
        connection.setApiProtocol(request.apiProtocol());
        connection.setAuthMode(request.authMode() != null ? request.authMode() : LlmAuthMode.BEARER);
        if (StringUtils.hasText(request.apiKey())) {
            connection.replaceApiKey(request.apiKey(), secrets);
        }
        if (request.enabled() != null) {
            connection.setEnabled(request.enabled());
        }

        WorkspaceLlmConnection saved;
        try {
            saved = connectionRepository.save(connection);
        } catch (DataIntegrityViolationException e) {
            throw new LlmConnectionSlugConflictException(slug, e);
        }
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_LLM_CONNECTION,
                saved.getId(),
                workspaceId,
                WorkspaceLlmConnectionSnapshot.of(saved)));
        return saved;
    }

    private String connectionSlug(Long workspaceId, @Nullable String requested, String displayName) {
        return CatalogSlug.unique(
                requested,
                displayName,
                slug -> connectionRepository
                        .findByWorkspaceIdAndSlug(workspaceId, slug)
                        .isPresent());
    }

    @Transactional
    public WorkspaceLlmConnection update(
            WorkspaceContext workspaceContext, Long id, UpdateWorkspaceLlmConnectionRequestDTO request) {
        WorkspaceLlmConnection connection = connectionRepository
                .findByIdAndWorkspaceIdForUpdate(id, workspaceContext.id())
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceLlmConnection", id));
        WorkspaceLlmConnectionSnapshot before = WorkspaceLlmConnectionSnapshot.of(connection);

        if (request.displayName() != null) {
            connection.setDisplayName(request.displayName());
        }
        if (Boolean.TRUE.equals(request.clearConnectionPlatform())) {
            connection.setConnectionPlatform(null);
        } else if (request.connectionPlatform() != null) {
            connection.setConnectionPlatform(request.connectionPlatform());
        }
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            connection.replaceApiKey(null, secrets);
        } else if (request.apiKey() != null) {
            connection.replaceApiKey(request.apiKey(), secrets);
        }
        if (request.enabled() != null) {
            connection.setEnabled(request.enabled());
        }

        WorkspaceLlmConnection saved = connectionRepository.save(connection);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_LLM_CONNECTION,
                saved.getId(),
                workspaceContext.id(),
                before,
                WorkspaceLlmConnectionSnapshot.of(saved)));
        return saved;
    }

    @Transactional
    public void delete(WorkspaceContext workspaceContext, Long id) {
        WorkspaceLlmConnection connection = load(workspaceContext, id);
        if (modelRepository.existsByConnectionIdAndWorkspaceId(id, workspaceContext.id())) {
            throw new LlmConnectionInUseException(id);
        }
        WorkspaceLlmConnectionSnapshot before = WorkspaceLlmConnectionSnapshot.of(connection);
        connectionRepository.delete(connection);
        configAudit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.WORKSPACE_LLM_CONNECTION, id, workspaceContext.id(), before));
    }

    /** Deliberately NOT {@code @Transactional} — see {@link LlmModelLimitDiscovery}. */
    public WorkspaceLlmProbeResultDTO probe(WorkspaceContext workspaceContext, Long id) {
        return WorkspaceLlmProbeResultDTO.from(limitDiscovery.probeWorkspaceConnection(workspaceContext.id(), id));
    }

    private void requireByoEnabled() {
        if (!instanceLlmSettingsService.get().isAllowWorkspaceConnections()) {
            throw new AccessForbiddenException("This server does not allow you to connect your own AI provider.");
        }
    }

    @Override
    @Transactional
    @WorkspaceAgnostic("Seals the AI provider keys of every workspace again during a system key rotation")
    public int resealWithActiveKey(SystemSecrets secrets) {
        int resealed = 0;
        for (var row : connectionRepository.lockAllForKeyRotation()) {
            if (row.resealWithActiveKey(secrets)) resealed++;
        }
        return resealed;
    }
}

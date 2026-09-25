package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.LlmConnectionAudit;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * CRUD for instance-owned LLM connections.
 *
 * <p>Audited on {@code auth_event} rather than {@code config_audit_event}: the config trail is
 * workspace-scoped and this catalog is global.
 */
@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance LLM connection catalog is global (app_admin-owned), not tenant-scoped")
@ConditionalOnServerRole
public class LlmConnectionService {

    private final LlmConnectionRepository connectionRepository;
    private final LlmModelRepository modelRepository;
    private final EgressPolicy egressPolicy;
    private final LlmConnectionAudit llmConnectionAudit;

    @Transactional(readOnly = true)
    public List<LlmConnection> list() {
        return connectionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public LlmConnection get(Long id) {
        return connectionRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("LlmConnection", id));
    }

    @Transactional
    public LlmConnection create(CreateLlmConnectionRequestDTO request) {
        String slug = connectionSlug(request.slug(), request.displayName());
        if (StringUtils.hasText(request.slug())
                && connectionRepository.findBySlug(slug).isPresent()) {
            throw new LlmConnectionSlugConflictException(slug);
        }
        egressPolicy.validate(request.baseUrl());

        LlmConnection connection = new LlmConnection();
        connection.setSlug(slug);
        connection.setDisplayName(request.displayName());
        connection.setConnectionPlatform(request.connectionPlatform());
        connection.setBaseUrl(request.baseUrl().trim());
        connection.setApiProtocol(request.apiProtocol());
        connection.setAuthMode(request.authMode() != null ? request.authMode() : LlmAuthMode.BEARER);
        if (StringUtils.hasText(request.apiKey())) {
            connection.setApiKey(request.apiKey());
        }
        if (request.enabled() != null) {
            connection.setEnabled(request.enabled());
        }

        LlmConnection saved;
        try {
            saved = connectionRepository.save(connection);
        } catch (DataIntegrityViolationException e) {
            throw new LlmConnectionSlugConflictException(slug, e);
        }
        llmConnectionAudit.connectionCreated(saved.getId(), saved.getSlug());
        return saved;
    }

    private String connectionSlug(@Nullable String requested, String displayName) {
        return CatalogSlug.unique(
                requested,
                displayName,
                slug -> connectionRepository.findBySlug(slug).isPresent());
    }

    @Transactional
    public LlmConnection update(Long id, UpdateLlmConnectionRequestDTO request) {
        LlmConnection connection =
                connectionRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("LlmConnection", id));

        if (request.displayName() != null) {
            connection.setDisplayName(request.displayName());
        }
        if (Boolean.TRUE.equals(request.clearConnectionPlatform())) {
            connection.setConnectionPlatform(null);
        } else if (request.connectionPlatform() != null) {
            connection.setConnectionPlatform(request.connectionPlatform());
        }
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            connection.setApiKey(null);
        } else if (request.apiKey() != null) {
            connection.setApiKey(request.apiKey());
        }
        if (request.enabled() != null) {
            connection.setEnabled(request.enabled());
        }

        LlmConnection saved = connectionRepository.save(connection);
        llmConnectionAudit.connectionUpdated(saved.getId(), saved.getSlug());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        LlmConnection connection =
                connectionRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("LlmConnection", id));
        if (modelRepository.existsByConnectionId(id)) {
            throw new LlmConnectionInUseException(id);
        }
        connectionRepository.delete(connection);
        llmConnectionAudit.connectionDeleted(connection.getId(), connection.getSlug());
    }
}

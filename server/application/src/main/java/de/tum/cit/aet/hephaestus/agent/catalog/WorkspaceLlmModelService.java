package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * CRUD for models on a workspace's own "bring your own" LLM connection, plus the available-models
 * projection a workspace admin picks a Task's model from.
 *
 * <p>Unlike the instance catalog, a workspace model carries its price inline rather than as a temporal
 * history.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceLlmModelService {

    private final WorkspaceLlmModelRepository modelRepository;
    private final WorkspaceLlmConnectionRepository connectionRepository;
    private final LlmModelRepository instanceModelRepository;
    private final LlmModelPriceRepository instancePriceRepository;
    private final WorkspaceAgentBindingRepository agentBindingRepository;
    private final InstanceLlmSettingsService instanceLlmSettingsService;
    private final ConfigAuditPort configAudit;

    @Transactional(readOnly = true)
    public List<WorkspaceLlmModel> list(WorkspaceContext workspaceContext) {
        return modelRepository.findByWorkspaceIdWithConnection(workspaceContext.id());
    }

    @Transactional(readOnly = true)
    public WorkspaceLlmModel get(WorkspaceContext workspaceContext, Long id) {
        return load(workspaceContext, id);
    }

    private WorkspaceLlmModel load(WorkspaceContext workspaceContext, Long id) {
        return modelRepository
                .findByIdAndWorkspaceIdWithConnection(id, workspaceContext.id())
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceLlmModel", id));
    }

    @Transactional
    public WorkspaceLlmModel create(
            WorkspaceContext workspaceContext, Long connectionId, CreateWorkspaceLlmModelRequestDTO request) {
        requireByoEnabled();
        Long workspaceId = workspaceContext.id();
        WorkspaceLlmConnection connection = connectionRepository
                .findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceLlmConnection", connectionId));
        String slug = modelSlug(workspaceId, request.slug(), request.displayName());
        if (StringUtils.hasText(request.slug())
                && modelRepository.findByWorkspaceIdAndSlug(workspaceId, slug).isPresent()) {
            throw new LlmModelSlugConflictException(connectionId, slug);
        }
        if (modelRepository.existsByConnectionIdAndUpstreamModelId(connectionId, request.upstreamModelId())) {
            throw new LlmModelUpstreamIdConflictException(connectionId, request.upstreamModelId());
        }

        PricingMode pricingMode = request.pricingMode() != null ? request.pricingMode() : PricingMode.UNPRICED;

        WorkspaceLlmModel model = new WorkspaceLlmModel();
        model.setWorkspace(connection.getWorkspace());
        model.setConnection(connection);
        model.setSlug(slug);
        model.setDisplayName(request.displayName());
        model.setUpstreamModelId(request.upstreamModelId());
        model.setContextWindow(request.contextWindow());
        model.setMaxOutputTokens(request.maxOutputTokens());
        if (request.supportsReasoning() != null) {
            model.setSupportsReasoning(request.supportsReasoning());
        }
        if (request.processingLocation() != null) {
            model.setProcessingLocation(request.processingLocation());
        }
        if (request.enabled() != null) {
            model.setEnabled(request.enabled());
        }
        validateAndApplyPrice(
                model,
                pricingMode,
                request.per1mInputUsd(),
                request.per1mOutputUsd(),
                request.per1mCacheReadUsd(),
                request.per1mCacheWriteUsd(),
                request.priceNote());
        if (model.isEnabled()) {
            requireActivatable(model);
        }

        WorkspaceLlmModel saved;
        try {
            saved = modelRepository.saveAndFlush(model);
        } catch (DataIntegrityViolationException e) {
            if (isUpstreamIdConflict(e)) {
                throw new LlmModelUpstreamIdConflictException(connectionId, request.upstreamModelId(), e);
            }
            throw new LlmModelSlugConflictException(connectionId, slug, e);
        }
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_LLM_MODEL,
                saved.getId(),
                workspaceId,
                WorkspaceLlmModelSnapshot.of(saved)));
        return saved;
    }

    private String modelSlug(Long workspaceId, @Nullable String requested, String displayName) {
        return CatalogSlug.unique(
                requested,
                displayName,
                slug -> modelRepository
                        .findByWorkspaceIdAndSlug(workspaceId, slug)
                        .isPresent());
    }

    @Transactional
    public WorkspaceLlmModel update(
            WorkspaceContext workspaceContext, Long id, UpdateWorkspaceLlmModelRequestDTO request) {
        WorkspaceLlmModel model = modelRepository
                .findByIdAndWorkspaceIdForUpdate(id, workspaceContext.id())
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceLlmModel", id));
        WorkspaceLlmModelSnapshot before = WorkspaceLlmModelSnapshot.of(model);

        if (request.displayName() != null) {
            model.setDisplayName(request.displayName());
        }
        if (request.contextWindow() != null) {
            model.setContextWindow(request.contextWindow());
        }
        if (request.maxOutputTokens() != null) {
            model.setMaxOutputTokens(request.maxOutputTokens());
        }
        if (request.supportsReasoning() != null) {
            model.setSupportsReasoning(request.supportsReasoning());
        }
        if (request.processingLocation() != null) {
            model.setProcessingLocation(request.processingLocation());
        }
        if (request.enabled() != null) {
            model.setEnabled(request.enabled());
        }
        if (request.pricingMode() != null) {
            validateAndApplyPrice(
                    model,
                    request.pricingMode(),
                    request.per1mInputUsd(),
                    request.per1mOutputUsd(),
                    request.per1mCacheReadUsd(),
                    request.per1mCacheWriteUsd(),
                    request.priceNote());
        }
        if (model.isEnabled()) {
            requireActivatable(model);
        }

        WorkspaceLlmModel saved = modelRepository.saveAndFlush(model);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_LLM_MODEL,
                saved.getId(),
                workspaceContext.id(),
                before,
                WorkspaceLlmModelSnapshot.of(saved)));
        return saved;
    }

    @Transactional
    public void delete(WorkspaceContext workspaceContext, Long id) {
        WorkspaceLlmModel model = load(workspaceContext, id);
        if (agentBindingRepository.existsByWorkspaceModelIdAndWorkspaceId(id, workspaceContext.id())) {
            throw new LlmModelInUseException(id);
        }
        WorkspaceLlmModelSnapshot before = WorkspaceLlmModelSnapshot.of(model);
        modelRepository.delete(model);
        configAudit.record(
                ConfigAuditEntry.deleted(ConfigAuditEntityType.WORKSPACE_LLM_MODEL, id, workspaceContext.id(), before));
    }

    /**
     * Deliberately not gated on {@code allow_workspace_connections}: already-bound BYO models must stay
     * explicable after an admin turns the setting off.
     */
    @Transactional(readOnly = true)
    public List<AvailableLlmModelDTO> availableModels(WorkspaceContext workspaceContext) {
        List<LlmModel> instanceModels = instanceModelRepository.findVisibleEnabledModels(workspaceContext.id());
        List<Long> instanceModelIds =
                instanceModels.stream().map(LlmModel::getId).toList();
        Map<Long, LlmModelPrice> currentPrices = instanceModelIds.isEmpty()
                ? Map.of()
                : instancePriceRepository.findByModelIdInAndEffectiveToIsNull(instanceModelIds).stream()
                        .collect(Collectors.toMap(price -> price.getModel().getId(), price -> price));

        List<WorkspaceLlmModel> workspaceModels =
                modelRepository.findEnabledWithEnabledConnection(workspaceContext.id());

        List<AvailableLlmModelDTO> result = new ArrayList<>(instanceModels.size() + workspaceModels.size());
        instanceModels.forEach(
                model -> result.add(AvailableLlmModelDTO.fromInstance(model, currentPrices.get(model.getId()))));
        workspaceModels.forEach(model -> result.add(AvailableLlmModelDTO.fromWorkspace(model)));
        return result;
    }

    private void requireByoEnabled() {
        if (!instanceLlmSettingsService.get().isAllowWorkspaceConnections()) {
            throw new AccessForbiddenException("Connecting your own AI provider is disabled on this server.");
        }
    }

    private static void validateAndApplyPrice(
            WorkspaceLlmModel model,
            PricingMode pricingMode,
            @Nullable BigDecimal per1mInputUsd,
            @Nullable BigDecimal per1mOutputUsd,
            @Nullable BigDecimal per1mCacheReadUsd,
            @Nullable BigDecimal per1mCacheWriteUsd,
            @Nullable String priceNote) {
        LlmPriceValidation.validate(
                pricingMode, per1mInputUsd, per1mOutputUsd, per1mCacheReadUsd, per1mCacheWriteUsd, priceNote);
        model.setPricingMode(pricingMode);
        model.setPer1mInputUsd(per1mInputUsd);
        model.setPer1mOutputUsd(per1mOutputUsd);
        model.setPer1mCacheReadUsd(per1mCacheReadUsd);
        model.setPer1mCacheWriteUsd(per1mCacheWriteUsd);
        model.setPriceNote(blankToNull(priceNote));
    }

    private static void requireActivatable(WorkspaceLlmModel model) {
        if (!model.getConnection().isEnabled() || model.getPricingMode() == PricingMode.UNPRICED) {
            throw new IllegalArgumentException(
                    "Activate the connection and configure a price before activating the model.");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value != null && value.isBlank() ? null : value;
    }

    private static boolean isUpstreamIdConflict(DataIntegrityViolationException e) {
        return DataIntegrityViolationConstraints.hasName(e, "ux_ws_llm_model_connection_upstream");
    }
}

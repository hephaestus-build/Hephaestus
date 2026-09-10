package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.LlmModelAudit;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD, pricing, and sharing for instance-owned LLM catalog models.
 *
 * <p>Audited on {@code auth_event} rather than {@code config_audit_event}: the config trail is
 * workspace-scoped and this catalog is global.
 */
@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance LLM model catalog is global (app_admin-owned), not tenant-scoped")
@ConditionalOnServerRole
public class LlmModelService {

    private final LlmModelRepository modelRepository;
    private final LlmConnectionRepository connectionRepository;
    private final LlmModelPriceRepository priceRepository;
    private final LlmModelWorkspaceGrantRepository grantRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceAgentBindingRepository agentBindingRepository;
    private final LlmModelAudit llmModelAudit;

    @Transactional(readOnly = true)
    public List<LlmModel> list() {
        return modelRepository.findAllWithConnection();
    }

    @Transactional(readOnly = true)
    public LlmModel get(Long id) {
        return modelRepository
                .findByIdWithConnection(id)
                .orElseThrow(() -> new EntityNotFoundException("LlmModel", id));
    }

    @Transactional(readOnly = true)
    public Map<Long, LlmModelPrice> currentPricesByModelId(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        return priceRepository.findByModelIdInAndEffectiveToIsNull(modelIds).stream()
                .collect(Collectors.toMap(price -> price.getModel().getId(), price -> price));
    }

    @Transactional(readOnly = true)
    public Map<Long, List<Long>> grantedWorkspaceIdsByModelId(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        return grantRepository.findByIdModelIdIn(modelIds).stream()
                .collect(Collectors.groupingBy(
                        grant -> grant.getId().getModelId(),
                        Collectors.mapping(grant -> grant.getId().getWorkspaceId(), Collectors.toList())));
    }

    @Transactional(readOnly = true)
    public List<Long> grantedWorkspaceIds(Long modelId) {
        return grantRepository.findByIdModelId(modelId).stream()
                .map(grant -> grant.getId().getWorkspaceId())
                .toList();
    }

    @Transactional(readOnly = true)
    public @Nullable LlmModelPrice currentPrice(Long modelId) {
        return priceRepository.findByModelIdAndEffectiveToIsNull(modelId).orElse(null);
    }

    @Transactional
    public LlmModel create(Long connectionId, CreateLlmModelRequestDTO request) {
        if (Boolean.TRUE.equals(request.enabled())) {
            throw new IllegalArgumentException("Create the model disabled, set its price, then activate it.");
        }
        LlmConnection connection = connectionRepository
                .findById(connectionId)
                .orElseThrow(() -> new EntityNotFoundException("LlmConnection", connectionId));
        String slug = modelSlug(connectionId, request.slug(), request.displayName());
        if (org.springframework.util.StringUtils.hasText(request.slug())
                && modelRepository.findByConnectionIdAndSlug(connectionId, slug).isPresent()) {
            throw new LlmModelSlugConflictException(connectionId, slug);
        }
        if (modelRepository.existsByConnectionIdAndUpstreamModelId(connectionId, request.upstreamModelId())) {
            throw new LlmModelUpstreamIdConflictException(connectionId, request.upstreamModelId());
        }

        LlmModel model = new LlmModel();
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
            if (request.enabled()) {
                requireActivatable(model);
            }
            model.setEnabled(request.enabled());
        }

        LlmModel saved;
        try {
            saved = modelRepository.saveAndFlush(model);
        } catch (DataIntegrityViolationException e) {
            if (isUpstreamIdConflict(e)) {
                throw new LlmModelUpstreamIdConflictException(connectionId, request.upstreamModelId(), e);
            }
            throw new LlmModelSlugConflictException(connectionId, slug, e);
        }
        llmModelAudit.modelCreated(saved.getId(), saved.getConnection().getId(), saved.getSlug());
        return saved;
    }

    private String modelSlug(Long connectionId, @Nullable String requested, String displayName) {
        return CatalogSlug.unique(
                requested,
                displayName,
                slug -> modelRepository
                        .findByConnectionIdAndSlug(connectionId, slug)
                        .isPresent());
    }

    private void requireActivatable(LlmModel model) {
        LlmModelPrice price =
                priceRepository.findByModelIdAndEffectiveToIsNull(model.getId()).orElse(null);
        if (!model.getConnection().isEnabled() || price == null || price.getPricingMode() == PricingMode.UNPRICED) {
            throw new IllegalArgumentException(
                    "Activate the connection and configure a price before activating the model.");
        }
    }

    @Transactional
    public LlmModel update(Long id, UpdateLlmModelRequestDTO request) {
        LlmModel model =
                modelRepository.findByIdForUpdate(id).orElseThrow(() -> new EntityNotFoundException("LlmModel", id));

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
        if (model.isEnabled()) {
            requireActivatable(model);
        }

        LlmModel saved = modelRepository.saveAndFlush(model);
        llmModelAudit.modelUpdated(saved.getId(), saved.getConnection().getId(), saved.getSlug());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        LlmModel model = modelRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("LlmModel", id));
        if (agentBindingRepository.existsByInstanceModelId(id)) {
            throw new LlmModelInUseException(id);
        }
        modelRepository.delete(model);
        llmModelAudit.modelDeleted(model.getId(), model.getConnection().getId(), model.getSlug());
    }

    /**
     * Repricing is temporal supersede-on-insert: {@code ux_llm_model_price_open} allows at most one open
     * price row per model, so the current one must be closed before the replacement is inserted.
     */
    @Transactional
    public LlmModelPrice updatePrice(Long modelId, UpdateLlmModelPriceRequestDTO request) {
        LlmModel model = modelRepository
                .findByIdForUpdate(modelId)
                .orElseThrow(() -> new EntityNotFoundException("LlmModel", modelId));
        validatePriceRequest(request);
        if (model.isEnabled() && request.pricingMode() == PricingMode.UNPRICED) {
            throw new IllegalArgumentException("Disable the model before changing its price to UNPRICED.");
        }

        Instant now = Instant.now();
        priceRepository.findByModelIdAndEffectiveToIsNull(modelId).ifPresent(open -> {
            open.setEffectiveTo(now);
            // saveAndFlush, not save: Hibernate orders queued INSERTs ahead of queued UPDATEs, so
            // without an explicit flush the new open row reaches the database while this one is
            // still open and ux_llm_model_price_open rejects the reprice.
            priceRepository.saveAndFlush(open);
        });

        LlmModelPrice price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(request.pricingMode());
        price.setPer1mInputUsd(request.per1mInputUsd());
        price.setPer1mOutputUsd(request.per1mOutputUsd());
        price.setPer1mCacheReadUsd(request.per1mCacheReadUsd());
        price.setPer1mCacheWriteUsd(request.per1mCacheWriteUsd());
        price.setNote(blankToNull(request.note()));
        price.setEffectiveFrom(now);

        LlmModelPrice saved = priceRepository.save(price);
        llmModelAudit.modelPriceChanged(modelId, saved.getPricingMode().name());
        return saved;
    }

    private static void validatePriceRequest(UpdateLlmModelPriceRequestDTO request) {
        LlmPriceValidation.validate(
                request.pricingMode(),
                request.per1mInputUsd(),
                request.per1mOutputUsd(),
                request.per1mCacheReadUsd(),
                request.per1mCacheWriteUsd(),
                request.note());
    }

    /** Replaces the model's grant set with exactly the requested workspaces. */
    @Transactional
    public LlmModel updateSharing(Long modelId, UpdateLlmModelSharingRequestDTO request) {
        LlmModel model = modelRepository
                .findByIdForUpdate(modelId)
                .orElseThrow(() -> new EntityNotFoundException("LlmModel", modelId));

        List<LlmModelWorkspaceGrant> existing = grantRepository.findByIdModelId(modelId);

        if (request.visibility() == ModelVisibility.PUBLIC) {
            if (!existing.isEmpty()) {
                grantRepository.deleteAll(existing);
            }
        } else {
            Set<Long> requested =
                    request.workspaceIds() != null ? new LinkedHashSet<>(request.workspaceIds()) : Set.of();
            assertWorkspacesExist(requested);

            Set<Long> existingWorkspaceIds = existing.stream()
                    .map(grant -> grant.getId().getWorkspaceId())
                    .collect(Collectors.toSet());

            List<LlmModelWorkspaceGrant> toRemove = existing.stream()
                    .filter(grant -> !requested.contains(grant.getId().getWorkspaceId()))
                    .toList();
            if (!toRemove.isEmpty()) {
                grantRepository.deleteAll(toRemove);
            }

            Instant now = Instant.now();
            List<LlmModelWorkspaceGrant> toAdd = requested.stream()
                    .filter(workspaceId -> !existingWorkspaceIds.contains(workspaceId))
                    .map(workspaceId -> {
                        LlmModelWorkspaceGrant grant = new LlmModelWorkspaceGrant(modelId, workspaceId);
                        grant.setGrantedAt(now);
                        return grant;
                    })
                    .toList();
            if (!toAdd.isEmpty()) {
                grantRepository.saveAll(toAdd);
            }
        }

        model.setVisibility(request.visibility());
        LlmModel saved = modelRepository.save(model);
        int workspaceCount = request.visibility() == ModelVisibility.GRANTED && request.workspaceIds() != null
                ? new LinkedHashSet<>(request.workspaceIds()).size()
                : 0;
        llmModelAudit.modelSharingChanged(modelId, saved.getVisibility().name(), workspaceCount);
        return saved;
    }

    private void assertWorkspacesExist(Set<Long> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return;
        }
        Set<Long> found = workspaceRepository.findAllById(workspaceIds).stream()
                .map(Workspace::getId)
                .collect(Collectors.toSet());
        Set<Long> unknown = new LinkedHashSet<>(workspaceIds);
        unknown.removeAll(found);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Cannot share with unknown workspace(s): "
                    + unknown.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value != null && value.isBlank() ? null : value;
    }

    private static boolean isUpstreamIdConflict(DataIntegrityViolationException e) {
        return DataIntegrityViolationConstraints.hasName(e, "ux_llm_model_connection_upstream");
    }
}

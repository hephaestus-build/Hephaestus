package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.auth.spi.LlmSettingsAudit;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The instance admin's pools of provider capacity and the models in them. */
@Service
@RequiredArgsConstructor
public class LlmCallPoolService {

    private final LlmCallPoolRepository pools;
    private final LlmModelRepository instanceModels;
    private final WorkspaceLlmModelRepository workspaceModels;
    private final ObjectProvider<LlmSettingsAudit> audit;

    @Transactional(readOnly = true)
    public List<LlmCallPool> list() {
        return pools.findAll(Sort.by("id"));
    }

    @Transactional(readOnly = true)
    public Optional<LlmCallPool> find(@Nullable Long id) {
        return id == null ? Optional.empty() : pools.findById(id);
    }

    @Transactional
    public LlmCallPool create(SaveLlmCallPoolRequestDTO request) {
        if (pools.existsBySlug(request.slug())) throw new LlmCallPoolSlugConflictException(request.slug());
        LlmCallPool pool = new LlmCallPool();
        pool.setSlug(request.slug());
        apply(pool, request);
        LlmCallPool saved = pools.save(pool);
        audit(saved, "CREATED");
        return saved;
    }

    @Transactional
    public LlmCallPool update(Long id, SaveLlmCallPoolRequestDTO request) {
        LlmCallPool pool = get(id);
        apply(pool, request);
        audit(pool, "UPDATED");
        return pool;
    }

    /** The pool's models are no longer limited; the permits of its calls in flight go with it. */
    @Transactional
    public void delete(Long id) {
        LlmCallPool pool = get(id);
        instanceModels.leaveCallPool(id);
        workspaceModels.leaveCallPool(id);
        pools.delete(pool);
        audit(pool, "DELETED");
    }

    /** Every model an instance admin can put into a pool, instance models first. */
    @Transactional(readOnly = true)
    public List<LlmCallPoolModelDTO> models() {
        List<LlmCallPoolModelDTO> models = new ArrayList<>();
        for (LlmModel model : instanceModels.findAllWithConnection()) {
            models.add(new LlmCallPoolModelDTO(
                    UsageFundingSource.INSTANCE,
                    model.getId(),
                    model.getDisplayName(),
                    model.getConnection().getDisplayName(),
                    host(model.getConnection().getBaseUrl()),
                    null,
                    model.getCallPoolId()));
        }
        for (WorkspaceLlmModel model : workspaceModels.findAllForCallPools()) {
            models.add(new LlmCallPoolModelDTO(
                    UsageFundingSource.WORKSPACE,
                    model.getId(),
                    model.getDisplayName(),
                    model.getConnection().getDisplayName(),
                    host(model.getConnection().getBaseUrl()),
                    model.getWorkspace().getWorkspaceSlug(),
                    model.getCallPoolId()));
        }
        return models;
    }

    /** Replaces the pool's models: a listed model joins it, also from another pool; an unlisted member leaves it. */
    @Transactional
    public void replaceModels(Long id, UpdateLlmCallPoolModelsRequestDTO request) {
        LlmCallPool pool = get(id);
        instanceModels.leaveCallPool(id);
        workspaceModels.leaveCallPool(id);
        join(id, request.instanceModelIds(), "LlmModel", instanceModels::joinCallPool);
        join(id, request.workspaceModelIds(), "WorkspaceLlmModel", workspaceModels::joinCallPool);
        audit(pool, "MODELS_CHANGED");
    }

    private interface Join {
        int apply(Long poolId, Collection<Long> ids);
    }

    private static void join(Long poolId, List<Long> ids, String entity, Join join) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        if (distinct.isEmpty()) return;
        // An unknown id rolls the whole change back, so the pool never holds half of what the admin chose.
        if (join.apply(poolId, distinct) != distinct.size()) {
            throw new EntityNotFoundException(entity, distinct.toString());
        }
    }

    private LlmCallPool get(Long id) {
        return pools.findById(id).orElseThrow(() -> new EntityNotFoundException("LlmCallPool", id));
    }

    private static void apply(LlmCallPool pool, SaveLlmCallPoolRequestDTO request) {
        pool.setDisplayName(request.displayName().strip());
        pool.setMaxConcurrentCalls(request.maxConcurrentCalls());
        pool.setInteractiveReserve(request.interactiveReserve());
    }

    private void audit(LlmCallPool pool, String change) {
        LlmSettingsAudit settingsAudit = audit.getIfAvailable();
        if (settingsAudit != null) settingsAudit.callPoolChanged(pool.getId(), pool.getSlug(), change);
    }

    private static @Nullable String host(String baseUrl) {
        try {
            return URI.create(baseUrl.strip()).getHost();
        } catch (IllegalArgumentException unparsable) {
            return null;
        }
    }
}

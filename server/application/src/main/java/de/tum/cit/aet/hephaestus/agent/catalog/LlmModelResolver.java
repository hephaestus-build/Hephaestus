package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a {@link ModelBindingSource} binding to the effective {@link ResolvedLlmModel} the runtime
 * needs, collapsing the two catalog scopes (instance vs workspace BYO) behind one shape. The credential
 * is resolved separately and live (never frozen) via {@link #resolveCredential}.
 */
@Service
@RequiredArgsConstructor
public class LlmModelResolver {

    private final LlmConnectionRepository llmConnectionRepository;
    private final WorkspaceLlmConnectionRepository workspaceLlmConnectionRepository;
    private final LlmModelRepository llmModelRepository;
    private final WorkspaceLlmModelRepository workspaceLlmModelRepository;
    private final LlmModelWorkspaceGrantRepository grantRepository;

    @Transactional(readOnly = true)
    public ResolvedLlmModel resolve(ModelBindingSource config) {
        LlmModel instance = config.getInstanceModel();
        if (instance != null) {
            if (!isUsable(instance, config.getWorkspace().getId())
                    || !matchesTier(config, instance.getDataHandlingTier())) {
                throw unavailable();
            }
            LlmConnection c = instance.getConnection();
            return new ResolvedLlmModel(
                    c.getBaseUrl(),
                    c.getApiProtocol().wire(),
                    instance.getUpstreamModelId(),
                    instance.getContextWindow(),
                    instance.getMaxOutputTokens(),
                    instance.getReasoningEffort(),
                    Boolean.TRUE.equals(instance.getForcedToolChoice()));
        }
        WorkspaceLlmModel byo = config.getWorkspaceModel();
        if (byo != null) {
            if (!isUsable(byo, config.getWorkspace().getId()) || !matchesTier(config, byo.getDataHandlingTier())) {
                throw unavailable();
            }
            WorkspaceLlmConnection c = byo.getConnection();
            return new ResolvedLlmModel(
                    c.getBaseUrl(),
                    c.getApiProtocol().wire(),
                    byo.getUpstreamModelId(),
                    byo.getContextWindow(),
                    byo.getMaxOutputTokens(),
                    byo.getReasoningEffort(),
                    Boolean.TRUE.equals(byo.getForcedToolChoice()));
        }
        throw new IllegalStateException("The binding does not reference a model");
    }

    /**
     * The same availability predicate {@link #resolve} enforces, as an answer instead of a throw, for
     * callers that only need to know whether a binding is usable.
     */
    @Transactional(readOnly = true)
    public boolean isAvailable(ModelBindingSource config) {
        LlmModel instance = config.getInstanceModel();
        if (instance != null) {
            return isUsable(instance, config.getWorkspace().getId())
                    && matchesTier(config, instance.getDataHandlingTier());
        }
        WorkspaceLlmModel byo = config.getWorkspaceModel();
        return byo != null
                && isUsable(byo, config.getWorkspace().getId())
                && matchesTier(config, byo.getDataHandlingTier());
    }

    /**
     * The slot rule is exact: a slot holds only a model whose derived tier equals it, so a model whose
     * facts later loosen drops out of its slot instead of serving a ceiling it no longer meets. The
     * {@code UNDECLARED} slot accepts any model.
     */
    private static boolean matchesTier(ModelBindingSource source, DataHandlingTier tier) {
        return source.getDataHandlingTier() == DataHandlingTier.UNDECLARED || source.getDataHandlingTier() == tier;
    }

    @Transactional(readOnly = true)
    public Optional<DataHandlingTier> dataHandlingTier(ConnectionRef ref) {
        if (ref.modelId() == null || ref.workspaceId() == null) return Optional.empty();
        if (ref.scope() == UsageFundingSource.INSTANCE) {
            return llmModelRepository.findById(ref.modelId()).map(LlmModel::getDataHandlingTier);
        }
        if (ref.scope() != UsageFundingSource.WORKSPACE) return Optional.empty();
        return workspaceLlmModelRepository
                .findByIdAndWorkspaceId(ref.modelId(), ref.workspaceId())
                .map(WorkspaceLlmModel::getDataHandlingTier);
    }

    private boolean isUsable(LlmModel model, Long workspaceId) {
        boolean visible = model.getVisibility() == ModelVisibility.PUBLIC
                || grantRepository.existsByIdModelIdAndIdWorkspaceId(model.getId(), workspaceId);
        return model.isEnabled() && model.getConnection().isEnabled() && visible;
    }

    private boolean isUsable(WorkspaceLlmModel model, Long workspaceId) {
        return model.isEnabled()
                && model.getConnection().isEnabled()
                && model.getWorkspace().getId().equals(workspaceId);
    }

    private static LlmModelUnavailableException unavailable() {
        return new LlmModelUnavailableException("The configured model is not available");
    }

    @Transactional(readOnly = true)
    public @Nullable String resolveCredential(ModelBindingSource config) {
        LlmModel instance = config.getInstanceModel();
        if (instance != null) {
            return instance.getConnection().getApiKey();
        }
        WorkspaceLlmModel byo = config.getWorkspaceModel();
        if (byo != null) {
            return byo.getConnection().getApiKey();
        }
        return null;
    }

    /** Freeze the connection id without its credential so {@link ConfigSnapshot} can resolve a live credential for the same provider. */
    public record ConnectionRef(
            @Nullable UsageFundingSource scope,
            @Nullable Long connectionId,
            @Nullable Long modelId,
            @Nullable Long workspaceId) {
        public static final ConnectionRef NONE = new ConnectionRef(null, null, null, null);
    }

    /**
     * The live routing + credential material the proxy injects. Routing and credential must be resolved
     * TOGETHER from the live connection row: taking {@code baseUrl} from a job's frozen snapshot while
     * re-reading the key would send a rotated key to a since-abandoned host.
     *
     * @param apiKey {@code null} for a deliberately keyless connection (self-hosted vLLM/Ollama
     *     gateway) — the caller forwards without an auth header rather than refusing
     * @param callPoolId the model's {@link LlmCallPool}, read live so a changed pool applies to the next call;
     *     {@code null} for none
     */
    public record ProxyCredential(
            String baseUrl,
            String apiProtocol,
            LlmAuthMode authMode,
            String upstreamModelId,
            @Nullable String apiKey,
            @Nullable Long callPoolId) {

        /** A credential of a model in no pool. */
        public ProxyCredential(
                String baseUrl,
                String apiProtocol,
                LlmAuthMode authMode,
                String upstreamModelId,
                @Nullable String apiKey) {
            this(baseUrl, apiProtocol, authMode, upstreamModelId, apiKey, null);
        }
    }

    @Transactional(readOnly = true)
    public ConnectionRef connectionRef(ModelBindingSource config) {
        LlmModel instance = config.getInstanceModel();
        if (instance != null) {
            return new ConnectionRef(
                    UsageFundingSource.INSTANCE,
                    instance.getConnection().getId(),
                    instance.getId(),
                    config.getWorkspace().getId());
        }
        WorkspaceLlmModel byo = config.getWorkspaceModel();
        if (byo != null) {
            return new ConnectionRef(
                    UsageFundingSource.WORKSPACE,
                    byo.getConnection().getId(),
                    byo.getId(),
                    config.getWorkspace().getId());
        }
        return ConnectionRef.NONE;
    }

    /** An enabled instance model with its live credential, for an instance-level caller that has no workspace. */
    public record InstanceModel(
            ConnectionRef ref,
            ProxyCredential credential,
            LlmApiProtocol protocol,
            @Nullable ReasoningEffort reasoningEffort) {}

    /**
     * Resolves an instance model for instance-level work, such as product feedback drafting. Workspace grants and
     * visibility do not apply: the operator chose the model for the instance. Returns null when the model or its
     * connection is disabled or gone.
     */
    @Transactional(readOnly = true)
    public @Nullable InstanceModel resolveInstanceModel(long modelId) {
        LlmModel model =
                llmModelRepository.findById(modelId).filter(LlmModel::isEnabled).orElse(null);
        if (model == null) return null;
        LlmConnection c = model.getConnection();
        if (!c.isEnabled()) return null;
        return new InstanceModel(
                new ConnectionRef(UsageFundingSource.INSTANCE, c.getId(), model.getId(), null),
                new ProxyCredential(
                        c.getBaseUrl(),
                        c.getApiProtocol().wire(),
                        c.getAuthMode(),
                        model.getUpstreamModelId(),
                        blankToNull(c.getApiKey()),
                        model.getCallPoolId()),
                c.getApiProtocol(),
                model.getReasoningEffort());
    }

    /** Pool-lock rechecks must read the database even when the claim already loaded the model entity. */
    @Transactional(readOnly = true)
    public @Nullable Long resolveCallPool(ConnectionRef ref) {
        Long modelId = ref.modelId();
        Long connectionId = ref.connectionId();
        if (modelId == null || connectionId == null) return null;
        if (ref.scope() == UsageFundingSource.INSTANCE)
            return llmModelRepository.findCallPoolId(modelId, connectionId).orElse(null);
        Long workspaceId = ref.workspaceId();
        if (ref.scope() == UsageFundingSource.WORKSPACE && workspaceId != null)
            return workspaceLlmModelRepository
                    .findCallPoolId(modelId, connectionId, workspaceId)
                    .orElse(null);
        return null;
    }

    /**
     * Called by the LLM proxy at call time, NEVER at job-dispatch time: unlike the rest of a job's
     * {@code ConfigSnapshot}, key rotation, host repointing and connection disabling must reach an
     * in-flight job. Returns {@code null} when the model or connection is no longer usable.
     */
    @Transactional(readOnly = true)
    public @Nullable ProxyCredential resolveProxyCredential(ConnectionRef ref) {
        if (ref.scope() == UsageFundingSource.INSTANCE && ref.connectionId() != null) {
            if (!isUsableInstanceModel(ref)) {
                return null;
            }
            Long modelId = ref.modelId();
            if (modelId == null) return null;
            LlmModel model = llmModelRepository.findById(modelId).orElse(null);
            if (model == null) return null;
            return llmConnectionRepository
                    .findById(ref.connectionId())
                    .filter(LlmConnection::isEnabled)
                    .map(c -> new ProxyCredential(
                            c.getBaseUrl(),
                            c.getApiProtocol().wire(),
                            c.getAuthMode(),
                            model.getUpstreamModelId(),
                            blankToNull(c.getApiKey()),
                            model.getCallPoolId()))
                    .orElse(null);
        }
        if (ref.scope() == UsageFundingSource.WORKSPACE && ref.connectionId() != null) {
            if (!isUsableWorkspaceModel(ref)) {
                return null;
            }
            Long modelId = ref.modelId();
            Long workspaceId = ref.workspaceId();
            if (modelId == null || workspaceId == null) return null;
            WorkspaceLlmModel model = workspaceLlmModelRepository
                    .findByIdAndWorkspaceId(modelId, workspaceId)
                    .orElse(null);
            if (model == null) return null;
            return workspaceLlmConnectionRepository
                    .findById(ref.connectionId())
                    .filter(WorkspaceLlmConnection::isEnabled)
                    .map(c -> new ProxyCredential(
                            c.getBaseUrl(),
                            c.getApiProtocol().wire(),
                            c.getAuthMode(),
                            model.getUpstreamModelId(),
                            blankToNull(c.getApiKey()),
                            model.getCallPoolId()))
                    .orElse(null);
        }
        return null;
    }

    private boolean isUsableInstanceModel(ConnectionRef ref) {
        Long modelId = ref.modelId();
        Long workspaceId = ref.workspaceId();
        if (modelId == null || workspaceId == null) {
            return false;
        }
        return llmModelRepository
                .findById(modelId)
                .filter(LlmModel::isEnabled)
                .filter(model -> model.getConnection().getId().equals(ref.connectionId()))
                .filter(model -> model.getVisibility() == ModelVisibility.PUBLIC
                        || grantRepository.existsByIdModelIdAndIdWorkspaceId(model.getId(), workspaceId))
                .isPresent();
    }

    private boolean isUsableWorkspaceModel(ConnectionRef ref) {
        Long modelId = ref.modelId();
        Long workspaceId = ref.workspaceId();
        if (modelId == null || workspaceId == null) {
            return false;
        }
        return workspaceLlmModelRepository
                .findByIdAndWorkspaceId(modelId, workspaceId)
                .filter(WorkspaceLlmModel::isEnabled)
                .filter(model -> model.getConnection().getId().equals(ref.connectionId()))
                .isPresent();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}

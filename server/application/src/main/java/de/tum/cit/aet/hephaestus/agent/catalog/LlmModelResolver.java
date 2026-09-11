package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
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
                    c.getApiProtocol(),
                    instance.getUpstreamModelId(),
                    instance.getContextWindow(),
                    instance.getMaxOutputTokens(),
                    instance.isSupportsReasoning());
        }
        WorkspaceLlmModel byo = config.getWorkspaceModel();
        if (byo != null) {
            if (!isUsable(byo, config.getWorkspace().getId()) || !matchesTier(config, byo.getDataHandlingTier())) {
                throw unavailable();
            }
            WorkspaceLlmConnection c = byo.getConnection();
            return new ResolvedLlmModel(
                    c.getBaseUrl(),
                    c.getApiProtocol(),
                    byo.getUpstreamModelId(),
                    byo.getContextWindow(),
                    byo.getMaxOutputTokens(),
                    byo.isSupportsReasoning());
        }
        throw new IllegalStateException("The agent config must bind an available OpenAI-compatible model");
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
    public DataHandlingTier dataHandlingTier(ConnectionRef ref) {
        if (ref.modelId() == null || ref.workspaceId() == null) return DataHandlingTier.UNDECLARED;
        if (ref.scope() == FundingSource.INSTANCE) {
            return llmModelRepository
                    .findById(ref.modelId())
                    .map(LlmModel::getDataHandlingTier)
                    .orElse(DataHandlingTier.UNDECLARED);
        }
        return workspaceLlmModelRepository
                .findByIdAndWorkspaceId(ref.modelId(), ref.workspaceId())
                .map(WorkspaceLlmModel::getDataHandlingTier)
                .orElse(DataHandlingTier.UNDECLARED);
    }

    private boolean isUsable(LlmModel model, Long workspaceId) {
        boolean visible = model.getVisibility() == ModelVisibility.PUBLIC
                || grantRepository.existsByIdModelIdAndIdWorkspaceId(model.getId(), workspaceId);
        return (model.isEnabled()
                && model.getConnection().isEnabled()
                && isSupportedProtocol(model.getConnection().getApiProtocol())
                && visible);
    }

    private boolean isUsable(WorkspaceLlmModel model, Long workspaceId) {
        return (model.isEnabled()
                && model.getConnection().isEnabled()
                && isSupportedProtocol(model.getConnection().getApiProtocol())
                && model.getWorkspace().getId().equals(workspaceId));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("The configured OpenAI-compatible model is not available");
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

    /**
     * Identifies WHICH connection row funds a config's binding, without exposing any credential
     * material. Frozen into {@link de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot} so the proxy
     * can re-resolve the live credential for an in-flight job without re-reading the config's
     * (possibly since-changed) current binding.
     */
    public record ConnectionRef(
            @Nullable FundingSource scope,
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
     */
    public record ProxyCredential(
            String baseUrl,
            String apiProtocol,
            LlmAuthMode authMode,
            String upstreamModelId,
            @Nullable String apiKey) {}

    @Transactional(readOnly = true)
    public ConnectionRef connectionRef(ModelBindingSource config) {
        LlmModel instance = config.getInstanceModel();
        if (instance != null) {
            return new ConnectionRef(
                    FundingSource.INSTANCE,
                    instance.getConnection().getId(),
                    instance.getId(),
                    config.getWorkspace().getId());
        }
        WorkspaceLlmModel byo = config.getWorkspaceModel();
        if (byo != null) {
            return new ConnectionRef(
                    FundingSource.WORKSPACE,
                    byo.getConnection().getId(),
                    byo.getId(),
                    config.getWorkspace().getId());
        }
        return ConnectionRef.NONE;
    }

    /**
     * Called by the LLM proxy at call time, NEVER at job-dispatch time: unlike the rest of a job's
     * {@code ConfigSnapshot}, key rotation, host repointing and connection disabling must reach an
     * in-flight job. Returns {@code null} when the model or connection is no longer usable.
     */
    @Transactional(readOnly = true)
    public @Nullable ProxyCredential resolveProxyCredential(ConnectionRef ref) {
        if (ref.scope() == FundingSource.INSTANCE && ref.connectionId() != null) {
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
                    .filter(c -> isSupportedProtocol(c.getApiProtocol()))
                    .map(c -> new ProxyCredential(
                            c.getBaseUrl(),
                            c.getApiProtocol(),
                            c.getAuthMode(),
                            model.getUpstreamModelId(),
                            blankToNull(c.getApiKey())))
                    .orElse(null);
        }
        if (ref.scope() == FundingSource.WORKSPACE && ref.connectionId() != null) {
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
                    .filter(c -> isSupportedProtocol(c.getApiProtocol()))
                    .map(c -> new ProxyCredential(
                            c.getBaseUrl(),
                            c.getApiProtocol(),
                            c.getAuthMode(),
                            model.getUpstreamModelId(),
                            blankToNull(c.getApiKey())))
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

    private static boolean isSupportedProtocol(@Nullable String apiProtocol) {
        return "openai-completions".equals(apiProtocol) || "openai-responses".equals(apiProtocol);
    }
}

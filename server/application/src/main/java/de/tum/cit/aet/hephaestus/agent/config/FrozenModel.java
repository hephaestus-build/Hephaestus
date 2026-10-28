package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.AdmittedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One model of a practice review as {@link ConfigSnapshot} freezes it: the review's own model ({@link
 * ConfigSnapshot#model()}) or a model for practice scripts of another kind. The credential is never frozen:
 * {@link #connectionScope} and {@link #connectionId} only identify the connection, and the proxy
 * resolves its credential live.
 *
 * @param dataHandlingTier the tier slot of the binding that supplied this model
 * @param priceSnapshot the price frozen when the job was admitted; {@code null} until then
 */
public record FrozenModel(
        LlmApiProtocol apiProtocol,
        String baseUrl,
        String upstreamModelId,
        @Nullable UsageFundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        @Nullable DataHandlingTier dataHandlingTier,
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable LlmPriceSnapshot priceSnapshot,
        boolean forcedToolChoice) {
    public FrozenModel {
        Objects.requireNonNull(apiProtocol, "apiProtocol must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(upstreamModelId, "upstreamModelId must not be null");
    }

    /** Throws {@link IllegalStateException} when the bound model is not available. */
    public static FrozenModel from(ModelBindingSource source, LlmModelResolver resolver) {
        ResolvedLlmModel resolved = resolver.resolve(source);
        LlmModelResolver.ConnectionRef ref = resolver.connectionRef(source);
        return from(source, resolved, ref);
    }

    static FrozenModel from(ModelBindingSource source, ResolvedLlmModel resolved, LlmModelResolver.ConnectionRef ref) {
        return new FrozenModel(
                LlmApiProtocol.parse(resolved.apiProtocol()).orElseThrow(),
                resolved.baseUrl(),
                resolved.upstreamModelId(),
                ref.scope(),
                ref.connectionId(),
                ref.modelId(),
                ref.workspaceId(),
                source.getDataHandlingTier(),
                resolved.reasoningEffort(),
                null,
                resolved.forcedToolChoice());
    }

    public LlmModelResolver.ConnectionRef connectionRef() {
        return new LlmModelResolver.ConnectionRef(connectionScope, connectionId, modelId, workspaceId);
    }

    /** Whether admission still resolves to the catalog row and upstream model frozen here. */
    public boolean isAdmittedAs(AdmittedLlmModel admitted) {
        return admitted.connection().equals(connectionRef())
                && admitted.resolved().upstreamModelId().equals(upstreamModelId)
                && admitted.resolved().apiProtocol().equals(apiProtocol.wire())
                && Objects.equals(admitted.resolved().reasoningEffort(), reasoningEffort);
    }

    public FrozenModel withPriceSnapshot(@Nullable LlmPriceSnapshot price) {
        return new FrozenModel(
                apiProtocol,
                baseUrl,
                upstreamModelId,
                connectionScope,
                connectionId,
                modelId,
                workspaceId,
                dataHandlingTier,
                reasoningEffort,
                price,
                forcedToolChoice);
    }
}

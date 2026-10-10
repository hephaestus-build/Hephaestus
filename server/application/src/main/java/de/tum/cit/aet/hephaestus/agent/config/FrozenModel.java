package de.tum.cit.aet.hephaestus.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.AdmittedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One model of a practice review as {@link ConfigSnapshot} freezes it: the review's own model ({@link
 * ConfigSnapshot#model()}) or a precompute model of another kind. The credential is never frozen:
 * {@link #connectionScope} and {@link #connectionId} only identify the connection, and the proxy
 * resolves its credential live.
 *
 * @param apiProtocol a {@code String}, because a pre-catalog snapshot can hold a protocol that {@link
 *     de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol} does not name
 * @param dataHandlingTier the tier slot of the binding that supplied this model
 * @param priceSnapshot the price frozen when the job was admitted; {@code null} until then
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FrozenModel(
        String apiProtocol,
        String baseUrl,
        String upstreamModelId,
        @Nullable FundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        @Nullable DataHandlingTier dataHandlingTier,
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable LlmPriceSnapshot priceSnapshot) {
    public FrozenModel {
        Objects.requireNonNull(apiProtocol, "apiProtocol must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(upstreamModelId, "upstreamModelId must not be null");
    }

    /** Throws {@link IllegalStateException} when the bound model is not available. */
    public static FrozenModel from(ModelBindingSource source, LlmModelResolver resolver) {
        ResolvedLlmModel resolved = resolver.resolve(source);
        LlmModelResolver.ConnectionRef ref = resolver.connectionRef(source);
        return new FrozenModel(
                resolved.apiProtocol(),
                resolved.baseUrl(),
                resolved.upstreamModelId(),
                ref.scope(),
                ref.connectionId(),
                ref.modelId(),
                ref.workspaceId(),
                source.getDataHandlingTier(),
                resolved.reasoningEffort(),
                null);
    }

    public LlmModelResolver.ConnectionRef connectionRef() {
        return new LlmModelResolver.ConnectionRef(connectionScope, connectionId, modelId, workspaceId);
    }

    /** Whether admission still resolves to the catalog row and upstream model frozen here. */
    public boolean isAdmittedAs(AdmittedLlmModel admitted) {
        return admitted.connection().equals(connectionRef())
                && admitted.resolved().upstreamModelId().equals(upstreamModelId);
    }

    public FrozenModel withPriceSnapshot(LlmPriceSnapshot price) {
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
                price);
    }
}

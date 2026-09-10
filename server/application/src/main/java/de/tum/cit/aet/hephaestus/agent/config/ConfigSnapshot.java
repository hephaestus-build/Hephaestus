package de.tum.cit.aet.hephaestus.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmProcessingLocation;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable projection of a workspace's {@link WorkspaceAgentBinding} frozen at job submission time.
 *
 * <p>Stored as JSONB on {@link de.tum.cit.aet.hephaestus.agent.job.AgentJob#getConfigSnapshot()}.
 * The executor reads this snapshot instead of the live binding so that in-flight jobs are not
 * affected by binding changes.
 *
 * <p>SECURITY: everything frozen here is non-secret behaviour. The credential is never frozen —
 * {@link #connectionScope}/{@link #connectionId} only identify which connection row funds the job, so
 * the proxy re-resolves credential AND base URL live via
 * {@link LlmModelResolver#resolveProxyCredential}. The two must travel together: resolving a rotated
 * key while routing on this frozen {@link #baseUrl} would send the new key to a host the connection
 * no longer points at. {@link #baseUrl} is frozen only for non-proxy consumers that need a host
 * before any credential exists.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigSnapshot(
        int schemaVersion,
        String apiProtocol,
        String baseUrl,
        String upstreamModelId,
        // Read-only carry-over from pre-catalog snapshots; from() writes null.
        @Nullable String modelVersion,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        boolean supportsReasoning,
        @Nullable FundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        int timeoutSeconds,
        boolean allowInternet,
        @Nullable LlmPriceSnapshot priceSnapshot,
        @Nullable LlmProcessingLocation processingLocation) {
    /**
     * Bump only for a reshape (field removal, type change, semantic reinterpretation). Adding a
     * nullable field is compatible both ways and needs no bump.
     */
    public static final int SCHEMA_VERSION = 5;

    /**
     * Oldest persisted version that already uses this record's shape: at or above it a payload
     * deserializes straight through, below it {@link #fromLegacyJson} must translate.
     */
    private static final int CATALOG_SHAPE_MIN_VERSION = 4;

    public ConfigSnapshot {
        Objects.requireNonNull(apiProtocol, "apiProtocol must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(upstreamModelId, "upstreamModelId must not be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive, got: " + timeoutSeconds);
        }
    }

    public static ConfigSnapshot from(ModelBindingSource source, LlmModelResolver resolver) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        ResolvedLlmModel resolved = resolver.resolve(source);
        LlmModelResolver.ConnectionRef ref = resolver.connectionRef(source);
        return new ConfigSnapshot(
                SCHEMA_VERSION,
                resolved.apiProtocol(),
                resolved.baseUrl(),
                resolved.upstreamModelId(),
                null,
                resolved.contextWindow(),
                resolved.maxOutputTokens(),
                resolved.supportsReasoning(),
                ref.scope(),
                ref.connectionId(),
                ref.modelId(),
                ref.workspaceId(),
                source.getTimeoutSeconds(),
                source.isAllowInternet(),
                null,
                source.getProcessingLocation());
    }

    public ConfigSnapshot withPriceSnapshot(@Nullable LlmPriceSnapshot price) {
        return new ConfigSnapshot(
                schemaVersion,
                apiProtocol,
                baseUrl,
                upstreamModelId,
                modelVersion,
                contextWindow,
                maxOutputTokens,
                supportsReasoning,
                connectionScope,
                connectionId,
                modelId,
                workspaceId,
                timeoutSeconds,
                allowInternet,
                price,
                processingLocation);
    }

    public JsonNode toJson(ObjectMapper objectMapper) {
        return objectMapper.valueToTree(this);
    }

    /**
     * Deserialize from JSONB, rejecting a newer schema version so a rolling deploy cannot silently
     * misread a row an upgraded node wrote.
     */
    public static ConfigSnapshot fromJson(JsonNode node, ObjectMapper objectMapper) {
        Objects.requireNonNull(node, "node must not be null");
        int version = node.path("schemaVersion").asInt(0);
        if (version > SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "ConfigSnapshot schema version %d is newer than supported version %d. Upgrade the application server."
                            .formatted(version, SCHEMA_VERSION));
        }
        if (version < CATALOG_SHAPE_MIN_VERSION) {
            return fromLegacyJson(node);
        }
        return objectMapper.convertValue(node, ConfigSnapshot.class);
    }

    /**
     * Translates a pre-catalog snapshot into the current shape so historical rows stay readable.
     * {@code connectionScope}/{@code connectionId} stay null, so the proxy fails such a job closed.
     */
    private static ConfigSnapshot fromLegacyJson(JsonNode node) {
        String provider = node.path("llmProvider").asString("OPENAI");
        String apiProtocol;
        String defaultBaseUrl;
        switch (provider) {
            case "ANTHROPIC" -> {
                apiProtocol = "anthropic-messages";
                defaultBaseUrl = "https://api.anthropic.com";
            }
            case "AZURE_OPENAI" -> {
                apiProtocol = "azure-openai-responses";
                defaultBaseUrl = "";
            }
            default -> {
                apiProtocol = "openai-completions";
                defaultBaseUrl = "https://api.openai.com";
            }
        }
        String legacyBaseUrl = node.path("llmBaseUrl").asString(null);
        String baseUrl = legacyBaseUrl != null && !legacyBaseUrl.isBlank() ? legacyBaseUrl : defaultBaseUrl;
        String modelName = node.path("modelName").asString(null);
        String modelVersion = node.path("modelVersion").asString(null);
        int timeoutSeconds = node.path("timeoutSeconds").asInt(600);
        boolean allowInternet = node.path("allowInternet").asBoolean(false);
        return new ConfigSnapshot(
                node.path("schemaVersion").asInt(0),
                apiProtocol,
                baseUrl,
                modelName != null ? modelName : "",
                modelVersion,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                timeoutSeconds,
                allowInternet,
                null,
                LlmProcessingLocation.UNCLASSIFIED);
    }
}

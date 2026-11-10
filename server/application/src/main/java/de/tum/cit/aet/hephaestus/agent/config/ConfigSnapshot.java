package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Frozen non-secret runtime configuration. Credentials and their target URL resolve together live. */
public record ConfigSnapshot(
        int schemaVersion,
        FrozenModel model,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        int timeoutSeconds) {
    public static final int SCHEMA_VERSION = 10;

    public ConfigSnapshot {
        Objects.requireNonNull(model, "model");
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        timeoutSeconds = Math.max(timeoutSeconds, PracticePiAdapter.MIN_TIMEOUT_SECONDS);
    }

    public static ConfigSnapshot from(ModelBindingSource source, LlmModelResolver resolver) {
        var resolved = resolver.resolve(source);
        var model = FrozenModel.from(source, resolved, resolver.connectionRef(source));
        return new ConfigSnapshot(
                SCHEMA_VERSION,
                model,
                resolved.contextWindow(),
                resolved.maxOutputTokens(),
                source.getTimeoutSeconds());
    }

    public ConfigSnapshot withPriceSnapshot(@Nullable LlmPriceSnapshot price) {
        return new ConfigSnapshot(
                schemaVersion, model.withPriceSnapshot(price), contextWindow, maxOutputTokens, timeoutSeconds);
    }

    public JsonNode toJson(ObjectMapper mapper) {
        return mapper.valueToTree(this);
    }

    public static ConfigSnapshot fromJson(JsonNode node, ObjectMapper mapper) {
        Objects.requireNonNull(node, "node");
        int version = node.path("schemaVersion").asInt(-1);
        if (version != SCHEMA_VERSION)
            throw new IllegalStateException("Unsupported runtime config snapshot version: " + version);
        return mapper.convertValue(node, ConfigSnapshot.class);
    }
}

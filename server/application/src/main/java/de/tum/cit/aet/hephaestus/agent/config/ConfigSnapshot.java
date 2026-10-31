package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.util.Map;
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
        int timeoutSeconds,
        @Nullable Map<ModelKind, FrozenModel> practiceScript) {
    public static final int SCHEMA_VERSION = 9;

    public ConfigSnapshot {
        Objects.requireNonNull(model, "model");
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        timeoutSeconds = Math.max(timeoutSeconds, PracticePiAdapter.MIN_TIMEOUT_SECONDS);
        practiceScript = practiceScript == null ? null : Map.copyOf(practiceScript);
    }

    public static ConfigSnapshot from(ModelBindingSource source, LlmModelResolver resolver) {
        var resolved = resolver.resolve(source);
        var model = FrozenModel.from(source, resolved, resolver.connectionRef(source));
        return new ConfigSnapshot(
                SCHEMA_VERSION,
                model,
                resolved.contextWindow(),
                resolved.maxOutputTokens(),
                source.getTimeoutSeconds(),
                null);
    }

    public ConfigSnapshot withPriceSnapshot(@Nullable LlmPriceSnapshot price) {
        return new ConfigSnapshot(
                schemaVersion,
                model.withPriceSnapshot(price),
                contextWindow,
                maxOutputTokens,
                timeoutSeconds,
                practiceScript);
    }

    public ConfigSnapshot withPracticeScript(@Nullable Map<ModelKind, FrozenModel> slots) {
        return new ConfigSnapshot(schemaVersion, model, contextWindow, maxOutputTokens, timeoutSeconds, slots);
    }

    public Map<ModelKind, FrozenModel> practiceScriptModels() {
        return practiceScript == null ? Map.of() : practiceScript;
    }

    public @Nullable FrozenModel practiceScriptSlot(ModelKind kind) {
        return practiceScript == null ? null : practiceScript.get(kind);
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

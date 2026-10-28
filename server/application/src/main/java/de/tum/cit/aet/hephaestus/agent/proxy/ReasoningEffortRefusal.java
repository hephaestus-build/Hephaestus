package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A provider that takes no reasoning effort refuses every call of a Heph binding that sets one, so each Heph answer
 * fails. The operator's remedy is to clear the binding's effort, so the warning names the effort the call sent. It
 * comes only when the provider's error names the effort parameter, and once a day for each model and effort.
 *
 * <p>The error body is the provider's, so it is read only for that decision and never logged: it can quote the request.
 */
final class ReasoningEffortRefusal {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEffortRefusal.class);

    /** The parameter as Chat Completions and the Responses API name it. */
    private static final String[] EFFORT_PARAMETERS = {"reasoning_effort", "reasoning.effort"};

    private record Refused(
            @Nullable UsageFundingSource scope, @Nullable Long modelId, ReasoningEffort effort) {}

    private final ObjectMapper objectMapper;
    private final Cache<Refused, Boolean> warned = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofDays(1))
            .build();

    ReasoningEffortRefusal(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Warns when the provider answered a Heph call with status 400 because of the reasoning effort the call sent. */
    void warnIfRefused(ProxyRouting routing, @Nullable UpstreamResult upstream, byte[] requestBody) {
        byte[] errorBody = upstream == null || upstream.status() != 400 ? null : upstream.body();
        if (errorBody == null) return;
        ReasoningEffort effort = requestedEffort(requestBody);
        if (effort == null || !namesTheEffort(errorBody)) return;
        Refused refused = new Refused(routing.connectionScope(), routing.modelId(), effort);
        if (warned.asMap().putIfAbsent(refused, Boolean.TRUE) != null) return;
        log.warn(
                "LLM provider refused the reasoning effort {} of a Heph model call for principal {}; if its model does"
                        + " not reason, set the effort of the Heph binding to Provider default",
                effort,
                routing.principalDescription());
    }

    /** The effort a call sends: {@code reasoning_effort} on Chat Completions, {@code reasoning.effort} on responses. */
    private @Nullable ReasoningEffort requestedEffort(byte[] body) {
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode effort = request.has("reasoning_effort")
                    ? request.path("reasoning_effort")
                    : request.path("reasoning").path("effort");
            return effort.isString() ? ReasoningEffort.valueOf(effort.asString().toUpperCase(Locale.ROOT)) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Whether the provider's error names the effort parameter in its {@code param}, {@code code} or {@code type}. The
     * OpenAI shape nests these under {@code error}; some OpenAI-compatible servers put them at the top level. The
     * message is free text, so it is not read.
     */
    private boolean namesTheEffort(byte[] errorBody) {
        try {
            JsonNode body = objectMapper.readTree(errorBody);
            JsonNode error = body.path("error").isObject() ? body.path("error") : body;
            return Stream.of("param", "code", "type")
                    .map(error::path)
                    .filter(JsonNode::isString)
                    .map(field -> field.asString().toLowerCase(Locale.ROOT))
                    .anyMatch(field -> Stream.of(EFFORT_PARAMETERS).anyMatch(field::contains));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}

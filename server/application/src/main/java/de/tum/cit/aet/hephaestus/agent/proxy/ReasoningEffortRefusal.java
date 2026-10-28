package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A provider that takes no reasoning effort refuses every call of a Heph binding that sets one, so each Heph answer
 * fails. The operator's remedy is to clear the binding's effort, so the warning names the effort the call sent, never
 * the provider's words, which can quote the request.
 */
final class ReasoningEffortRefusal {

    private static final Logger log = LoggerFactory.getLogger(ReasoningEffortRefusal.class);

    private ReasoningEffortRefusal() {}

    /** Warns when the provider answered a Heph call with status 400 while the call sent a reasoning effort. */
    static void warnIfRefused(
            ObjectMapper objectMapper, ProxyRouting routing, @Nullable UpstreamResult upstream, byte[] body) {
        if (upstream == null || upstream.status() != 400) return;
        ReasoningEffort effort = requestedEffort(objectMapper, body);
        if (effort != null)
            log.warn(
                    "LLM upstream refused a Heph model call for principal {} with status=400 while the Heph binding "
                            + "sets reasoning effort {}; if its model does not reason, set the effort to Provider default",
                    routing.principalDescription(),
                    effort);
    }

    /** The effort a call sends: {@code reasoning_effort} on chat completions, {@code reasoning.effort} on responses. */
    private static @Nullable ReasoningEffort requestedEffort(ObjectMapper objectMapper, byte[] body) {
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
}

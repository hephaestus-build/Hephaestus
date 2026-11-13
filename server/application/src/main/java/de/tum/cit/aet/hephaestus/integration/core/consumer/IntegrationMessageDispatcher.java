package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure-function dispatcher: event subject → {@link IntegrationMessageHandler}.
 *
 * <p>Splits the routing problem into three independent steps:
 * <ol>
 *   <li>Map subject prefix → {@link IntegrationKind} via {@link
 *       ConsumerSubjectMath#kindFromSubjectPrefix(String)} (the single provider-routing
 *       allow-list; never calls {@link IntegrationKind#valueOf(String)} on subject
 *       input).</li>
 *   <li>Hand the full subject to that kind's registered {@link SubjectParser} to obtain
 *       an {@link EventTypeKey}.</li>
 *   <li>Look the key up in the {@link IntegrationMessageHandlerRegistry}.</li>
 * </ol>
 *
 * <p>Each step short-circuits to {@link Optional#empty()} on miss; the framework
 * decides what to do (ACK and ignore vs. dead-letter) at the consumer layer. The
 * dispatcher itself performs NO I/O beyond the eventual handler invocation, so it
 * remains a fast, unit-testable function suitable for tight loops and benchmarks.
 *
 * <p><b>Construction-time validation.</b> Two {@link SubjectParser} beans for the same
 * {@link IntegrationKind} are a fatal configuration error (we cannot pick a winner
 * deterministically). This matches the registry's duplicate-key policy.
 */
@Component
public class IntegrationMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IntegrationMessageDispatcher.class);

    private final IntegrationMessageHandlerRegistry registry;
    private final Map<IntegrationKind, SubjectParser> parsersByKind;

    public IntegrationMessageDispatcher(IntegrationMessageHandlerRegistry registry, List<SubjectParser> parsers) {
        this.registry = registry;
        Map<IntegrationKind, SubjectParser> map = new EnumMap<>(IntegrationKind.class);
        for (SubjectParser parser : parsers) {
            IntegrationKind kind = parser.kind();
            if (kind == null) {
                throw new IllegalStateException(parser.getClass().getName()
                        + " returned null from kind() — every SubjectParser must declare a kind");
            }
            SubjectParser previous = map.putIfAbsent(kind, parser);
            if (previous != null) {
                throw new IllegalStateException("Duplicate SubjectParser for kind " + kind
                        + ": "
                        + previous.getClass().getName()
                        + " conflicts with "
                        + parser.getClass().getName());
            }
        }
        this.parsersByKind = Map.copyOf(map);
        log.info(
                "IntegrationMessageDispatcher configured: {} subject parser(s), {} handler(s)",
                parsersByKind.size(),
                registry.handlerCount());
    }

    /**
     * Look up the handler that owns the given subject. Returns empty if any link in the
     * chain (prefix → kind → key → handler) is missing.
     */
    public Optional<IntegrationMessageHandler> dispatch(String fullSubject) {
        Optional<IntegrationKind> kind = ConsumerSubjectMath.kindFromSubjectPrefix(fullSubject);
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        SubjectParser parser = parsersByKind.get(kind.get());
        if (parser == null) {
            return Optional.empty();
        }
        EventTypeKey key;
        try {
            key = parser.parse(fullSubject);
        } catch (RuntimeException e) {
            // Malformed subject: the parser is the single source of truth for what
            // "well-formed" means per kind. We surface this to the consumer layer at
            // DEBUG since the consumer will already log/handle it via its own error
            // path; double-logging here just creates noise.
            log.debug(
                    "SubjectParser rejected subject: route={}, error={}",
                    ConsumerSubjectMath.routeLabel(fullSubject),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
        // A handler may declare itself disabled (e.g. its feature flag is off). Filtering
        // it out here reproduces "disabled → no handler → ack+skip" semantics per-message.
        return registry.resolve(key).filter(IntegrationMessageHandler::isEnabled);
    }

    /** Number of {@link SubjectParser} bindings — one per registered kind. */
    public int parserCount() {
        return parsersByKind.size();
    }
}

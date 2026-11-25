package de.tum.cit.aet.hephaestus.core.evaluation;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled Enable the internal evaluation clock, settle barrier and observation stream on the server role.
 * @param collectorUrl HTTP endpoint that accepts the ID-only events from an isolated evaluation server.
 */
@ConfigurationProperties("hephaestus.evaluation.controlled-clock")
@ConditionalOnEvaluation
public record EvaluationProperties(
        boolean enabled, @Nullable URI collectorUrl) {}

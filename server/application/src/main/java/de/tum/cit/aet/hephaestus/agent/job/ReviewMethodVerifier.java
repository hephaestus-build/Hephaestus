package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import java.util.LinkedHashMap;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Component
@RequiredArgsConstructor
public class ReviewMethodVerifier {
    private final LlmModelResolver models;
    private final PracticeReviewProperties properties;

    public FrozenModel freeze(ModelBindingSource binding) {
        var model = models.resolve(binding);
        var ref = models.connectionRef(binding);
        if (ref.scope() == null || ref.modelId() == null)
            throw new IllegalStateException("The selected model has no durable identity");
        var nodes = JsonNodeFactory.instance;
        var options = new LinkedHashMap<String, JsonNode>();
        if (model.contextWindow() != null) options.put("contextWindow", nodes.numberNode(model.contextWindow()));
        if (model.maxOutputTokens() != null) options.put("maxOutputTokens", nodes.numberNode(model.maxOutputTokens()));
        if (properties.samplingTemperature() != null)
            options.put("temperature", nodes.numberNode(properties.samplingTemperature()));
        options.put("practiceModelCalls", nodes.numberNode(properties.practiceModelCalls()));
        options.put("practiceOutputTokens", nodes.numberNode(properties.practiceOutputTokens()));
        options.put("practiceInputTokens", nodes.numberNode(properties.practiceInputTokens()));
        return new FrozenModel(
                ref.scope().name().toLowerCase(Locale.ROOT) + ":" + ref.modelId(),
                model.apiProtocol(),
                model.upstreamModelId(),
                model.reasoningEffort() == null
                        ? null
                        : model.reasoningEffort().name().toLowerCase(Locale.ROOT),
                options);
    }

    public void require(ReviewMethod method, String slot, ModelBindingSource binding) {
        var expected = method.models().stream()
                .filter(model -> model.slot().equals(slot))
                .findFirst()
                .orElseThrow(() ->
                        new ReviewJobStateConflictException("The pinned method is missing a required model slot"));
        if (!freeze(binding).equals(expected.model()))
            throw new ReviewJobStateConflictException(
                    "The configured model or judgment options changed after the review was pinned");
    }
}

package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import com.fasterxml.jackson.annotation.JsonFormat;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code mentor_turn.admission} document: the model, rates and route of a turn attempt, and the model shape that
 * the Heph host configures the session with. {@link MentorTurnCompletion} bills from it, also an attempt whose host
 * died. Rates are JSON strings, so a per-1M rate never passes through a binary64. {@link #read} never throws: billing
 * runs inside the per-turn transaction, and a throw would leave the attempt unbilled.
 */
public record MentorTurnAdmission(
        String model,
        LlmPriceSnapshot price,
        Route route,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort) {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnAdmission.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .withConfigOverride(
                    BigDecimal.class, rates -> rates.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
            .build();

    public MentorTurnAdmission {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(route, "route");
    }

    /**
     * Where an admitted attempt's model calls go: the same non-secret connection reference a practice review freezes
     * into its config snapshot. The proxy resolves the live credential from it on every call.
     */
    public record Route(
            String apiProtocol,
            String baseUrl,
            @Nullable FundingSource connectionScope,
            @Nullable Long connectionId,
            @Nullable Long modelId) {}

    public static MentorTurnAdmission of(MentorLlmConfig llm, LlmPriceSnapshot price) {
        return new MentorTurnAdmission(
                llm.upstreamModelId(),
                price,
                new Route(llm.apiProtocol(), llm.baseUrl(), llm.connectionScope(), llm.connectionId(), llm.modelId()),
                llm.contextWindow(),
                llm.maxOutputTokens(),
                llm.reasoningEffort());
    }

    public JsonNode toJson() {
        return MAPPER.valueToTree(this);
    }

    /** The admission a turn carries; empty when it carries none or it cannot be read. */
    public static Optional<MentorTurnAdmission> read(@Nullable JsonNode document) {
        if (document == null || !document.isObject()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.treeToValue(document, MentorTurnAdmission.class));
        } catch (JacksonException unreadable) {
            log.warn("Unreadable mentor turn admission: {}", unreadable.getMessage());
            return Optional.empty();
        }
    }

    /** The rates a turn was admitted with, or an unpriced snapshot when they cannot be read. */
    public static LlmPriceSnapshot priceOf(@Nullable JsonNode document) {
        return read(document).map(MentorTurnAdmission::price).orElseGet(LlmPriceSnapshot::unpricedInstance);
    }
}

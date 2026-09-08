package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelVisibility;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import java.math.BigDecimal;

/**
 * Object-mother for the instance LLM catalog entities. Both factories return <b>unsaved</b> entities
 * with only the fields the catalog requires — persist them through the repository the test already
 * has, and set anything else on the returned instance (both entities are fully mutable).
 */
public final class LlmCatalogTestFixtures {

    public static final String OPENAI_COMPLETIONS = "openai-completions";

    /**
     * A syntactically valid, deliberately non-resolvable host. {@code .example} is reserved by
     * RFC 2606, so a fixture connection can never accidentally egress to a real provider.
     */
    public static final String BASE_URL = "https://api.openai.example/v1";

    /** Public IP literal for URL-validation tests: no DNS lookup or HTTP request is needed. */
    public static final String PUBLIC_BASE_URL = "https://8.8.8.8/v1";

    private LlmCatalogTestFixtures() {}

    public static LlmConnection connection(String slug) {
        LlmConnection connection = new LlmConnection();
        connection.setSlug(slug);
        connection.setDisplayName("Connection " + slug);
        connection.setBaseUrl(BASE_URL);
        connection.setApiProtocol(OPENAI_COMPLETIONS);
        connection.setEnabled(true);
        return connection;
    }

    public static LlmModel model(LlmConnection connection, String slug, String upstreamModelId) {
        return model(connection, slug, upstreamModelId, ModelVisibility.PUBLIC, true);
    }

    public static LlmModel model(
            LlmConnection connection,
            String slug,
            String upstreamModelId,
            ModelVisibility visibility,
            boolean enabled) {
        LlmModel model = new LlmModel();
        model.setConnection(connection);
        model.setSlug(slug);
        model.setDisplayName("Model " + slug);
        model.setUpstreamModelId(upstreamModelId);
        model.setVisibility(visibility);
        model.setEnabled(enabled);
        return model;
    }

    /**
     * Input alone is priced, at $10 per million tokens, so a turn's expected cost reads directly off
     * its input-token count. There is deliberately no unpriced variant: admission refuses to start a
     * turn without a price snapshot, so one would let tests exercise an unreachable state.
     */
    public static MentorLlmConfig admittedMentorConfig() {
        return new MentorLlmConfig(
                "openai-responses",
                BASE_URL,
                "test-model",
                null,
                null,
                false,
                FundingSource.INSTANCE,
                1L,
                1L,
                null,
                new LlmPriceSnapshot(
                        FundingSource.INSTANCE,
                        PricingState.PRICED,
                        12L,
                        null,
                        new BigDecimal("10"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO),
                false,
                600);
    }
}

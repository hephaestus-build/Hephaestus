package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewMethodVerifierTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LlmModelResolver models = mock(LlmModelResolver.class);
    private final ModelBindingSource binding = mock(ModelBindingSource.class);
    private final ReviewMethodVerifier verifier = new ReviewMethodVerifier(
            models,
            new PracticeReviewProperties(false, 15, 5, 0.5, 12, 16000, 3, 200000, 1000, Duration.ofMinutes(10)));

    private ReviewMethod persisted() {
        when(models.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://models.example", "openai-completions", "model", 32000, 16000, null));
        when(models.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 1L, 2L, 3L));
        var method = new ReviewMethod(
                1, "a".repeat(64), List.of(new BoundModelSlot("review", ModelKind.CHAT, verifier.freeze(binding))));
        return mapper.readValue(mapper.writeValueAsBytes(method), ReviewMethod.class);
    }

    @Test
    void shouldAcceptUnchangedNumericOptionsAfterTheirJsonRoundTrip() {
        var method = persisted();
        assertThatCode(() -> verifier.require(method, "review", binding)).doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseAnActualOptionChangeAfterItsJsonRoundTrip() {
        var method = persisted();
        when(models.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://models.example", "openai-completions", "model", 32000, 16001, null));
        assertThatThrownBy(() -> verifier.require(method, "review", binding))
                .isInstanceOf(ReviewJobStateConflictException.class);
    }
}

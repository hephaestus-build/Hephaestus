package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class FrozenModelTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(longs = {1000, 200000, 2147483648L, Long.MAX_VALUE})
    void shouldPreserveMethodEqualityThroughJsonPersistence(long limit) {
        var pinned = new ReviewMethod(
                1, "pinned-models", List.of(new BoundModelSlot("review", ModelKind.CHAT, model(limit))));
        var restored = mapper.readValue(mapper.writeValueAsString(pinned), ReviewMethod.class);
        assertThat(restored).isEqualTo(pinned);
        assertThat(restored.hashCode()).isEqualTo(pinned.hashCode());
    }

    @Test
    void shouldKeepChangedOptionsDistinctFromThePinnedMethod() {
        assertThat(model(200001)).isNotEqualTo(model(200000));
    }

    private static FrozenModel model(long limit) {
        return new FrozenModel(
                "instance:1",
                "openai_completions",
                "test-model",
                null,
                Map.of(
                        "practiceOutputTokens",
                        JsonNodeFactory.instance.numberNode(limit),
                        "practiceModelCalls",
                        JsonNodeFactory.instance.numberNode(36L)));
    }
}

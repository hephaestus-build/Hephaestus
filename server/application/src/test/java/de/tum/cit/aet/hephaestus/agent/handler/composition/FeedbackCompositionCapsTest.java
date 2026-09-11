package de.tum.cit.aet.hephaestus.agent.handler.composition;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FeedbackCompositionCapsTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private JsonNode request;

    @BeforeEach
    void stageAnEventReview() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        FeedbackCompositionInputs.stage(files, ObservationOrigin.LIVE);
        request = objectMapper.readTree(
                new String(files.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH), StandardCharsets.UTF_8));
    }

    @Test
    void stagesTwoInAppUnitsPerRun() {
        assertThat(request.get("channels")
                        .get(FeedbackChannel.IN_APP.name())
                        .get("maxUnits")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void requiresTwoDistinctArtifactsForPatternClaims() {
        assertThat(request.get("minDistinctArtifacts").asInt()).isEqualTo(2);
    }
}

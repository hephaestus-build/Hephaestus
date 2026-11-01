package de.tum.cit.aet.hephaestus.agent.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class MentorLlmConfigTest extends BaseUnitTest {

    @Test
    void shouldEndATurnAtTheHephCeilingWhenItsBindingStoredTheReviewCeiling() {
        assertThat(config(AgentBindingLimits.MAX_TIMEOUT_SECONDS).timeoutSeconds())
                .isEqualTo(AgentBindingLimits.MAX_HEPH_TURN_SECONDS);
        assertThat(config(120).timeoutSeconds()).isEqualTo(120);
    }

    @Test
    void shouldGiveATurnTheWholeCeilingOfFifteenMinutesAndNotTheFormerFive() {
        assertThat(AgentBindingLimits.MAX_HEPH_TURN_SECONDS).isEqualTo(900);
        assertThat(config(900).timeoutSeconds()).isEqualTo(900);
        assertThat(config(901).timeoutSeconds()).isEqualTo(900);
    }

    private static MentorLlmConfig config(int timeoutSeconds) {
        return new MentorLlmConfig(
                "openai-completions",
                "https://models.example.test/v1",
                "test-model",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                timeoutSeconds);
    }
}

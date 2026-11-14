package de.tum.cit.aet.hephaestus.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class ConversationLlmConfigTest extends BaseUnitTest {

    @Test
    void shouldEndATurnAtTheHephaestusCeilingWhenItsBindingStoredTheReviewCeiling() {
        assertThat(config(ModelAssignmentLimits.MAX_TIMEOUT_SECONDS).timeoutSeconds())
                .isEqualTo(ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS);
        assertThat(config(120).timeoutSeconds()).isEqualTo(120);
    }

    @Test
    void shouldGiveATurnTheWholeCeilingOfFifteenMinutesAndNotTheFormerFive() {
        assertThat(ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS).isEqualTo(900);
        assertThat(config(900).timeoutSeconds()).isEqualTo(900);
        assertThat(config(901).timeoutSeconds()).isEqualTo(900);
    }

    private static ConversationLlmConfig config(int timeoutSeconds) {
        return new ConversationLlmConfig(
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

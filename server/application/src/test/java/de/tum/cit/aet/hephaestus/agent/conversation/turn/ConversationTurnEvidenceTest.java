package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.providers.conversation.PreparedConversationFeedbackContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.conversation.ResultHistoryContentSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConversationTurnEvidenceTest extends BaseUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldNameTheWaitingFeedbackByIdAndPracticeWithoutItsWordsAndPointToTheIndex() {
        String prepared = """
                {"waiting":4,"preparedConversationFeedback":[
                  {"feedbackId":"7f1c2e3d-4b5a-4c6d-8e9f-0a1b2c3d4e5f","body":"BRIEF-NOT-IN-SITUATION",
                   "practiceSlugs":["describe-why"],"resultIds":["RESULT-NOT-IN-SITUATION"]}]}
                """;

        String receipt = receipt(Map.of(PreparedConversationFeedbackContentSource.OUTPUT_KEY, prepared));

        assertThat(receipt)
                .isEqualTo("Prepared feedback waiting for them in this conversation: 4, among them"
                        + " 7f1c2e3d-4b5a-4c6d-8e9f-0a1b2c3d4e5f (describe-why). Read"
                        + " inputs/context/prepared_conversation_feedback.json before you raise one.\n"
                        + ConversationTurnEvidence.START)
                .doesNotContain("NOT-IN-SITUATION");
    }

    @Test
    void shouldOnlyPointToTheIndexWhenNoFeedbackWaits() {
        assertThat(receipt(Map.of(
                        PreparedConversationFeedbackContentSource.OUTPUT_KEY,
                        "{\"waiting\":0,\"preparedConversationFeedback\":[]}",
                        ResultHistoryContentSource.OUTPUT_KEY,
                        "{\"results\":[{\"practiceSlug\":\"RESULT-NOT-IN-SITUATION\"}]}")))
                .isEqualTo(ConversationTurnEvidence.START);
        assertThat(receipt(Map.of(PreparedConversationFeedbackContentSource.OUTPUT_KEY, "not json")))
                .isEqualTo(ConversationTurnEvidence.START);
    }

    private String receipt(Map<String, String> inputs) {
        Map<String, byte[]> encoded = new HashMap<>();
        inputs.forEach((key, value) -> encoded.put(key, value.getBytes(StandardCharsets.UTF_8)));
        return ConversationTurnEvidence.receipt(mapper, encoded);
    }
}

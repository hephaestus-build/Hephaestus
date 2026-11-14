package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ConversationTurnPartialDTOTest extends BaseUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCarryTheStageTheHostNamed() {
        ConversationTurnPartialDTO partial = ConversationTurnPartialDTO.from(mapper.readTree("""
                {"activity":"tool","tool":"fetch_context","stage":"Reading your practice feedback…","messages":[]}
                """));

        assertThat(partial.activity()).isEqualTo(ConversationTurnPartialDTO.Activity.TOOL);
        assertThat(partial.stage()).isEqualTo("Reading your practice feedback…");
    }

    @Test
    void shouldReadASnapshotWithoutAStageAsThinking() {
        ConversationTurnPartialDTO partial = ConversationTurnPartialDTO.from(mapper.readTree("""
                {"activity":"thinking","tool":null,"messages":[]}
                """));

        assertThat(partial.stage()).isEqualTo("Thinking…");
    }
}

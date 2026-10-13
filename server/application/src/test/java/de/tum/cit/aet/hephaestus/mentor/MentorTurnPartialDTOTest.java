package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MentorTurnPartialDTOTest extends BaseUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCarryTheStageTheHostNamed() {
        MentorTurnPartialDTO partial = MentorTurnPartialDTO.from(mapper.readTree("""
                {"activity":"tool","tool":"fetch_context","stage":"Reading your practice feedback…","messages":[]}
                """));

        assertThat(partial.activity()).isEqualTo(MentorTurnPartialDTO.Activity.TOOL);
        assertThat(partial.stage()).isEqualTo("Reading your practice feedback…");
    }

    @Test
    void shouldReadASnapshotWithoutAStageAsThinking() {
        MentorTurnPartialDTO partial = MentorTurnPartialDTO.from(mapper.readTree("""
                {"activity":"thinking","tool":null,"messages":[]}
                """));

        assertThat(partial.stage()).isEqualTo("Thinking…");
    }
}

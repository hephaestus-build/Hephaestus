package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Split;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What a split sends members, as JSON: every part, or only its total. */
class AggregateTotalOnlyTest extends BaseUnitTest {

    @Test
    void shouldSendOnlyTheTotalWhenOnePartHoldsFourPeople() {
        var sent = sent(new Split(4, 0, 6, 7));

        assertThat(sent.path("people").asInt()).isEqualTo(17);
        assertThat(sent.path("noneYet").isNull()).isTrue();
        assertThat(sent.path("parts").valueStream().map(part -> part.path("people")))
                .hasSize(3)
                .allMatch(JsonNode::isNull);
    }

    @Test
    void shouldSendEveryPartWhenEachHoldsNoneOrFiveOrMore() {
        var sent = sent(new Split(5, 0, 6, 7));

        assertThat(sent.path("people").asInt()).isEqualTo(18);
        assertThat(sent.path("noneYet").asInt()).isEqualTo(7);
        assertThat(sent.path("parts")
                        .valueStream()
                        .map(part -> part.path("people").asInt()))
                .containsExactly(5, 0, 6);
    }

    @Test
    void shouldSendOnlyTheTotalWhenAllSixPeopleShareOnePart() {
        var sent = sent(new Split(0, 0, 6, 0));

        assertThat(sent.path("people").asInt()).isEqualTo(6);
        assertThat(sent.path("parts").valueStream().map(part -> part.path("people")))
                .allMatch(JsonNode::isNull);
    }

    @Test
    void shouldSendNeitherTotalNorPartsWhenFourPeopleAreCounted() {
        var sent = sent(new Split(2, 0, 2, 0));

        assertThat(sent.path("people").isNull()).isTrue();
        assertThat(sent.path("noneYet").isNull()).isTrue();
        assertThat(sent.path("parts").valueStream().map(part -> part.path("people")))
                .allMatch(JsonNode::isNull);
    }

    private static JsonNode sent(Split split) {
        return new JsonMapper().valueToTree(WorkspaceSplits.shown(split));
    }
}

package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class CriteriaCaseSelectionTest {

    private static final String LABEL = "dependency-label";
    private static final String IMPORT_ONLY = "dependency-import-without-declaration-edit";

    private static JsonNode cases() throws IOException {
        return JsonMapper.builder()
                .build()
                .readTree(Path.of("src/test/resources/practices/criteria-cases.json")
                        .toFile());
    }

    private static List<String> ids(List<JsonNode> selected) {
        return selected.stream().map(scenario -> scenario.path("id").asString()).toList();
    }

    @Test
    void shouldSendEveryCaseInFileOrderWhenNoSelectionIsGiven() throws IOException {
        JsonNode cases = cases();

        assertThat(ids(CriteriaCaseSelection.select(cases, null)))
                .containsExactlyElementsOf(StreamSupport.stream(cases.spliterator(), false)
                        .map(scenario -> scenario.path("id").asString())
                        .toList());
    }

    @Test
    void shouldSendExactlyTheNamedCasesByTheirIdsWhicheverOrderTheyAreNamedIn() throws IOException {
        assertThat(ids(CriteriaCaseSelection.select(cases(), " " + IMPORT_ONLY + " , " + LABEL)))
                .containsExactly(LABEL, IMPORT_ONLY);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                ",",
                LABEL + ",",
                LABEL + ",," + IMPORT_ONLY,
                LABEL + "," + LABEL,
            })
    void shouldRejectAMalformedSelectionRatherThanSendEveryCase(String selection) throws IOException {
        JsonNode cases = cases();

        assertThatThrownBy(() -> CriteriaCaseSelection.select(cases, selection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CriteriaCaseSelection.PROPERTY);
    }

    @Test
    void shouldRejectAnUnknownIdEvenBesideAKnownOne() throws IOException {
        JsonNode cases = cases();

        assertThatThrownBy(() -> CriteriaCaseSelection.select(cases, LABEL + ",no-such-case"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-case")
                .hasMessageNotContaining(LABEL);
    }
}

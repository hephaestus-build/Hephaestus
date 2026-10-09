package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings.Standing;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PracticeProfileContentSourceTest extends BaseUnitTest {

    @Mock
    DeveloperPracticeStandings standings;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    PracticeProfileContentSource source;

    @Test
    void shouldGroupStandingsByPracticeGroupAndNameNeverByStanding() {
        when(standings.standings(1L, 2L))
                .thenReturn(List.of(
                        standing("tests", "Tests", "Quality", "Needs work", true, 5),
                        standing("why", "Describe why", "Communication", "Holding", true, 2),
                        standing("docs", "Docs", "Quality", "Strong", true, 4),
                        standing("loose", "Loose", null, "No verdict yet", false, 0)));

        JsonNode payload = source.buildPayload(1L, 2L);

        assertThat(payload.get("groups")
                        .valueStream()
                        .map(group -> (group.get("group").isNull()
                                        ? "none"
                                        : group.get("group").asString()) + ": "
                                + String.join(
                                        ",",
                                        group.get("practices")
                                                .valueStream()
                                                .map(p -> p.get("slug").asString())
                                                .toList())))
                .as("by group, then by name; a stronger standing never moves a practice up")
                .containsExactly("Communication: why", "Quality: docs,tests", "none: loose");
        JsonNode early = payload.get("groups").get(0).get("practices").get(0);
        assertThat(early.get("earlyRead").asBoolean()).isTrue();
        assertThat(early.get("decidedPiecesOfWork").asInt()).isEqualTo(2);
        JsonNode undecided = payload.get("groups").get(2).get("practices").get(0);
        assertThat(undecided.get("standing").asString()).isEqualTo("No verdict yet");
        assertThat(undecided.has("earlyRead"))
                .as("no verdict, so no read to call early")
                .isFalse();
    }

    private static Standing standing(
            String slug, String name, @Nullable String group, String standing, boolean verdict, int decided) {
        return new Standing(slug, name, group, standing, verdict, decided, null, 0, 0, null);
    }
}

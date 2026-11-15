package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationPracticeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PracticeCatalogContentSourceTest extends BaseUnitTest {
    @Test
    void shouldStageTheDefinitionsTheCatalogOwnerAuthorized() {
        var practices = mock(ConversationPracticeQuery.class);
        when(practices.current(1))
                .thenReturn(List.of(new ConversationPracticeQuery.ConversationPractice(
                        "clear-names",
                        DefinitionTestFixtures.withAbout(
                                DefinitionTestFixtures.definition("Clear names", "Use clear names."), null))));
        var row = new PracticeCatalogContentSource(practices, new JsonMapper())
                .buildPayload(1L)
                .path("practices")
                .get(0);
        assertThat(row.path("slug").asString()).isEqualTo("clear-names");
        assertThat(row.path("displayName").asString()).isEqualTo("Clear names");
        assertThat(row.path("criteria").asString()).isEqualTo("Use clear names.");
    }

    @Test
    void shouldKeepAnEmptyAuthorizedCatalogEmpty() {
        var practices = mock(ConversationPracticeQuery.class);
        when(practices.current(1)).thenReturn(List.of());
        assertThat(new PracticeCatalogContentSource(practices, new JsonMapper())
                        .buildPayload(1L)
                        .path("practices"))
                .isEmpty();
    }

    @Test
    void shouldListWholePracticesUpToTheBoundAndNameEveryOneLeftOut() {
        var practices = mock(ConversationPracticeQuery.class);
        List<ConversationPracticeQuery.ConversationPractice> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(practice("p-%02d".formatted(i), "c"));
        }
        when(practices.current(1)).thenReturn(many);
        var source = new PracticeCatalogContentSource(practices, new JsonMapper());

        JsonNode byCount = source.buildPayload(1L);

        assertThat(byCount.get("totalPractices").asInt()).isEqualTo(25);
        assertThat(byCount.get("practices")).hasSize(20);
        assertThat(byCount.get("omittedPractices").valueStream().map(JsonNode::asString))
                .containsExactly("p-20", "p-21", "p-22", "p-23", "p-24");
        assertThat(byCount.get("truncated").asBoolean()).isTrue();

        String longCriteria = "x".repeat(30_000);
        when(practices.current(1))
                .thenReturn(
                        List.of(practice("a", longCriteria), practice("b", longCriteria), practice("c", longCriteria)));

        JsonNode bySize = source.buildPayload(1L);

        assertThat(bySize.get("practices")
                        .valueStream()
                        .map(p -> p.get("criteria").asString()))
                .as("never a practice cut inside its criterion")
                .containsOnly(longCriteria)
                .hasSize(2);
        assertThat(bySize.get("omittedPractices").valueStream().map(JsonNode::asString))
                .containsExactly("c");
        assertThat(bySize.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void shouldShortenTheOnePracticeLeftWhenItAloneIsTooLargeAndNameWhatItShortened() {
        var practices = mock(ConversationPracticeQuery.class);
        when(practices.current(1)).thenReturn(List.of(practice("huge", "z".repeat(200_000))));
        var mapper = new JsonMapper();

        JsonNode catalog = new PracticeCatalogContentSource(practices, mapper).buildPayload(1L);

        assertThat(mapper.writeValueAsString(catalog)).hasSizeLessThanOrEqualTo(64_000);
        JsonNode only = catalog.get("practices").get(0);
        assertThat(only.get("slug").asString()).isEqualTo("huge");
        assertThat(only.get("shortenedFields").valueStream().map(JsonNode::asString))
                .containsExactly("criteria");
        assertThat(catalog.get("truncated").asBoolean()).isTrue();
        assertThat(catalog.get("omittedPractices")).isEmpty();
    }

    private static ConversationPracticeQuery.ConversationPractice practice(String slug, String criteria) {
        return new ConversationPracticeQuery.ConversationPractice(
                slug, DefinitionTestFixtures.definition(slug, criteria));
    }
}

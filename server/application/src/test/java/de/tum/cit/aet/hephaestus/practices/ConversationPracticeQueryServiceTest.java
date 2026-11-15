package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationPracticeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ConversationPracticeQueryServiceTest extends BaseUnitTest {
    @Test
    void shouldReturnCurrentVisibleDefinitionsWhenEffectiveFeedbackModePermitsConversation() {
        var practices = mock(PracticeRepository.class);
        var versions = mock(PracticeVersionService.class);
        var defaults = mock(WorkspaceReviewDefaultsProvider.class);
        var group = new PracticeGroup();
        group.setFeedbackMode(FeedbackMode.APPROVE_FIRST);
        var hidden = new PracticeGroup();
        hidden.setShownToDevelopers(false);
        hidden.setFeedbackMode(FeedbackMode.AUTOMATIC);
        var explicit = practice("explicit", FeedbackMode.AUTOMATIC, null);
        var inherited = practice("inherited", null, group);
        when(defaults.rawDefaultFeedbackMode(7L)).thenReturn(FeedbackMode.OFF);
        when(practices.findAllForCatalog(7L))
                .thenReturn(List.of(
                        explicit,
                        practice("off", FeedbackMode.OFF, group),
                        inherited,
                        practice("hidden", FeedbackMode.AUTOMATIC, hidden),
                        practice("workspace-off", null, null)));
        var first = DefinitionTestFixtures.definition("Current explicit", "Current explicit criteria.");
        var second = DefinitionTestFixtures.definition("Current inherited", "Current inherited criteria.");
        when(versions.definitionOf(explicit.getCurrentVersion())).thenReturn(first);
        when(versions.definitionOf(inherited.getCurrentVersion())).thenReturn(second);

        assertThat(new ConversationPracticeQueryService(practices, versions, defaults).current(7))
                .containsExactly(
                        new ConversationPracticeQuery.ConversationPractice("explicit", first),
                        new ConversationPracticeQuery.ConversationPractice("inherited", second));
    }

    private static Practice practice(String slug, @Nullable FeedbackMode mode, @Nullable PracticeGroup group) {
        var practice = new Practice();
        practice.setSlug(slug);
        practice.setFeedbackMode(mode);
        practice.setGroup(group);
        practice.setCurrentVersion(TestEntities.practiceVersion());
        return practice;
    }
}

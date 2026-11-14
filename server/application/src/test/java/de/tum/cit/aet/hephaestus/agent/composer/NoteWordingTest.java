package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A conversation note speaks about the developer, in plain words, and keeps coordinates in its evidence. */
@Tag("unit")
class NoteWordingTest {
    private static final List<String> SLUGS = List.of("handles-errors", "describes-changes");

    @Test
    void shouldAcceptANoteThatSpeaksAboutTheDeveloperInPlainWords() {
        assertThat(NoteWording.problems(
                        "The developer catches the read error and returns null, so the caller cannot tell a failed"
                                + " fetch from an empty one. The description's closing line names no acceptance"
                                + " criteria; the line of reasoning stays open.",
                        SLUGS))
                .isEmpty();
    }

    @Test
    void shouldAskBackANoteThatSpeaksToTheDeveloperWhenItSaysYou() {
        assertThat(NoteWording.problems("Your catch returns null, so you lose the cause.", SLUGS))
                .singleElement()
                .asString()
                .contains("speaks to the developer");
    }

    @Test
    void shouldLeaveTheWorksOwnQuotedWordsAloneWhenTheyContainYou() {
        assertThat(NoteWording.problems(
                        "The description says \"you can retry later\" and `yourHandler` swallows the error.", SLUGS))
                .isEmpty();
    }

    @Test
    void shouldAskBackANoteThatUsesInternalWordsWhenItNamesAnOutcomeSeverityOrSlug() {
        assertThat(NoteWording.problems("This NOT_MET result is serious.", SLUGS))
                .hasSize(1);
        assertThat(NoteWording.problems("A high severity gap in the error path.", SLUGS))
                .hasSize(1);
        assertThat(NoteWording.problems("It falls under handles-errors here.", SLUGS))
                .hasSize(1);
    }

    @Test
    void shouldAskBackANoteThatNamesALineCoordinateWhenItGivesALineOrFilePosition() {
        assertThat(NoteWording.problems("The catch in description L16 drops it.", SLUGS))
                .hasSize(1);
        assertThat(NoteWording.problems("See lines 9-15 for the swallowed error.", SLUGS))
                .hasSize(1);
        assertThat(NoteWording.problems("The catch in Fetcher.java:42 drops the cause.", SLUGS))
                .hasSize(1);
    }

    @Test
    void shouldAskBackANoteThatStartsWithAHeadingOrFieldLabel() {
        assertThat(NoteWording.problems("Problem: the catch drops the cause.", SLUGS))
                .singleElement()
                .asString()
                .contains("field label");
        assertThat(NoteWording.problems("The developer names two causes: a timeout and a parse error.", SLUGS))
                .isEmpty();
        assertThat(NoteWording.problems("Evidence: the catch drops the cause.", SLUGS))
                .singleElement()
                .asString()
                .contains("field label");
    }
}

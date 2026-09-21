package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The producer writes this layout and the reader takes it apart, so the two must agree exactly. These
 * are the assertions that would fail if either side drifted.
 */
class InAppFeedbackBodyTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"Tests are arriving one commit late", "One word", "Punctuation: it's fine — really"})
    void roundTripsAnyHeadlineItWrote(String headline) {
        String body = InAppFeedbackBody.render(headline, "The message.", "Try this next.");

        assertThat(InAppFeedbackBody.headlineOf(body)).isEqualTo(headline);
    }

    @Test
    void handsOutTheMessageWithoutTheHeadlineOrTheNextStep() {
        String body = InAppFeedbackBody.render("Headline", "The message.\n\nWith two paragraphs.", "Try this next.");

        assertThat(InAppFeedbackBody.messageOf(body)).isEqualTo("The message.\n\nWith two paragraphs.");
        assertThat(InAppFeedbackBody.nextStepOf(body)).isEqualTo("Try this next.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Try this next.", "Split\nit", "Write the assertion **before** the branch"})
    void roundTripsAnyNextStepItWrote(String nextStep) {
        String body = InAppFeedbackBody.render("Headline", "The message.\n\nWith two paragraphs.", nextStep);

        assertThat(InAppFeedbackBody.nextStepOf(body)).isEqualTo(nextStep.replace('\n', ' '));
    }

    /** A newline in a heading would swallow the rest of the message into the title. */
    @Test
    void collapsesANewlineInTheHeadlineRatherThanLettingItSplitTheBody() {
        String body = InAppFeedbackBody.render("Two\nlines", "The message.", "Try this next.");

        assertThat(InAppFeedbackBody.headlineOf(body)).isEqualTo("Two lines");
        assertThat(InAppFeedbackBody.messageOf(body)).startsWith("The message.");
    }

    /**
     * Null rather than a guess: an in-context body was written by a different producer with no headline
     * at all, and inventing one would put words in its mouth.
     */
    @Test
    void reportsNoHeadlineForABodyItDidNotWrite() {
        assertThat(InAppFeedbackBody.headlineOf("A plain in-context comment body."))
                .isNull();
        assertThat(InAppFeedbackBody.messageOf("A plain in-context comment body."))
                .isEqualTo("A plain in-context comment body.");
        assertThat(InAppFeedbackBody.nextStepOf("A plain in-context comment body."))
                .isNull();
    }

    @Test
    void toleratesAnAbsentBody() {
        assertThat(InAppFeedbackBody.headlineOf(null)).isNull();
        assertThat(InAppFeedbackBody.messageOf(null)).isEmpty();
        assertThat(InAppFeedbackBody.nextStepOf(null)).isNull();
    }
}

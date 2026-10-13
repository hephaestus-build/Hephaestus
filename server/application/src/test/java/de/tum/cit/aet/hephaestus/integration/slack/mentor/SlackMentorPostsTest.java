package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorObservationLinkDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlackMentorPostsTest extends BaseUnitTest {

    @Test
    void shouldRecognizeAPostByItsMetadataWhenTheHistoryReturnsIt() {
        UUID entryId = UUID.randomUUID();
        Message tagged = new Message();
        tagged.setMetadata(SlackMentorPosts.metadata(entryId));
        Message other = new Message();
        other.setMetadata(new Message.Metadata(
                "heph_entry", Map.of("entry_id", UUID.randomUUID().toString())));

        Message untagged = new Message();
        untagged.setBotId("B1");

        assertThat(SlackMentorPosts.isPostOf(tagged, entryId)).isTrue();
        assertThat(SlackMentorPosts.isPostOf(other, entryId)).isFalse();
        assertThat(SlackMentorPosts.isPostOf(untagged, entryId)).isFalse();
    }

    @Test
    void shouldReadTheVoteAndTheAnswerWhenAFeedbackButtonIsPressed() {
        UUID answerId = UUID.randomUUID();

        assertThat(SlackMentorPosts.feedback("NOT_HELPFUL:" + answerId))
                .contains(new SlackMentorPosts.Feedback(answerId, MentorEntry.Vote.NOT_HELPFUL));
        assertThat(SlackMentorPosts.feedback("positive_feedback")).isEmpty();
        assertThat(SlackMentorPosts.feedback("MAYBE:" + answerId)).isEmpty();
    }

    @Test
    void shouldShowTheStartOfTheAnswerAsPlainTextWhenSlackNotifies() {
        assertThat(SlackMentorPosts.notification("## Reviews\n\nYour reviews are **quick**."))
                .isEqualTo("Reviews Your reviews are quick.");
        assertThat(SlackMentorPosts.notification("word ".repeat(100)))
                .endsWith("…")
                .hasSizeLessThan(290);
    }

    @Test
    void shouldCutTheMarkdownWithANoteWhenTheAnswerIsLongerThanSlackShows() {
        String shown = SlackMentorPosts.markdown("a".repeat(20_000)).getFirst().toString();

        assertThat(shown).contains("The full answer is in Hephaestus.");
    }

    private static MentorEntryDTO heph(String text, MentorObservationLinkDTO... observations) {
        return new MentorEntryDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                1,
                MentorEntryDTO.Kind.HEPH,
                MentorEntry.Surface.SLACK,
                text,
                null,
                UUID.randomUUID(),
                null,
                null,
                Instant.parse("2026-10-08T07:00:00Z"),
                null,
                false,
                null,
                List.of(observations),
                null,
                null);
    }

    @Test
    void shouldShowEveryMessageAndTheFeedbackItShowedInOrderWhenAReplyIsRendered() {
        UUID observationId = UUID.randomUUID();
        MentorObservationLinkDTO feedback = new MentorObservationLinkDTO(
                observationId,
                "Your description says what changed.\nSay why, too.",
                "explain-decisions",
                "Explain decisions",
                new ReviewedWorkRefDTO(
                        "421",
                        ArtifactKind.of("scm.pull_request"),
                        IntegrationKind.GITHUB,
                        "#421",
                        "Cache [the] catalog",
                        "https://github.com/acme/app/pull/421",
                        "acme/app"));

        SlackMentorPosts.Reply reply =
                SlackMentorPosts.reply(List.of(heph("Here is what I saw.", feedback), heph("What do you make of it?")));

        assertThat(reply.text()).isEqualTo("""
                        Here is what I saw.

                        > **Explain decisions**
                        > Your description says what changed.
                        > Say why, too.
                        > Seen on [#421 Cache \\[the\\] catalog](https://github.com/acme/app/pull/421)

                        What do you make of it?""");
        assertThat(reply.shownIn(reply.text())).containsExactly(observationId);
        assertThat(reply.shownIn(reply.text().substring(0, 40))).isEmpty();
    }
}

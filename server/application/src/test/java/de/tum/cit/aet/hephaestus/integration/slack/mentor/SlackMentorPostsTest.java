package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorLinkDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static MentorEntryDTO heph(String text, MentorLinkDTO... links) {
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
                null,
                false,
                null,
                List.of(links),
                null,
                null);
    }

    @Test
    void shouldShowEveryMessageAndWhatItShowedInOrderWhenAReplyIsRendered() {
        UUID feedbackId = UUID.randomUUID();
        MentorLinkDTO feedback = new MentorLinkDTO(
                MentorLinkDTO.Kind.FEEDBACK,
                feedbackId,
                "Your description says what changed.\nSay why, too.",
                null,
                421L);
        MentorLinkDTO result = new MentorLinkDTO(
                MentorLinkDTO.Kind.RESULT, UUID.randomUUID(), "The tests cover the cache.", "explain-decisions", 421L);

        SlackMentorPosts.Reply reply = SlackMentorPosts.reply(
                List.of(heph("Here is what I saw.", feedback, result), heph("What do you make of it?")), Set.of());

        assertThat(reply.text()).isEqualTo("""
                        Here is what I saw.

                        > Your description says what changed.
                        > Say why, too.

                        > The tests cover the cache.

                        What do you make of it?""");
        // Only shown prepared feedback is delivery; a result link is discussion.
        assertThat(reply.shownIn(reply.text()).whole()).containsExactly(feedbackId);
        assertThat(reply.shownIn(reply.text()).partly()).isEmpty();
        assertThat(reply.shownIn(reply.text().substring(0, 40)).whole()).isEmpty();
        assertThat(reply.shownIn(reply.text().substring(0, 40)).partly())
                .as("the cut falls inside the feedback's quote")
                .containsExactly(feedbackId);
        assertThat(reply.shownIn(reply.text().substring(0, 21)).partly())
                .as("the cut falls before the quote")
                .isEmpty();
    }

    @Test
    void shouldShowFeedbackOnlyPartlyWhenSlackCutsTheSecondOfItsTwoQuotes() {
        UUID feedbackId = UUID.randomUUID();
        MentorLinkDTO first = new MentorLinkDTO(
                MentorLinkDTO.Kind.FEEDBACK, feedbackId, "Your description says what changed.", null, 421L);
        MentorLinkDTO again =
                new MentorLinkDTO(MentorLinkDTO.Kind.FEEDBACK, feedbackId, "Say why you chose it, too.", null, 421L);
        SlackMentorPosts.Reply reply =
                SlackMentorPosts.reply(List.of(heph("Here is what I saw.", first, again)), Set.of());

        // Slack shows the first quote whole and the second one only in part.
        SlackMentorPosts.Reply.Seen seen =
                reply.shownIn(reply.text().substring(0, reply.text().length() - 5));

        assertThat(seen.whole()).isEmpty();
        assertThat(seen.partly()).containsExactly(feedbackId);
    }

    @Test
    void shouldShowALinkToAnotherHostAsCodeWhenHephaestusRepliesInSlack() {
        SlackMentorPosts.Reply reply = SlackMentorPosts.reply(
                List.of(
                        heph(
                                "Read [this guide](https://evil.example/?q=secret) or [the PR](https://github.com/a/b/pull/1).")),
                Set.of("github.com"));

        assertThat(reply.text())
                .isEqualTo(
                        "Read this guide (`https://evil.example/?q=secret`) or [the PR](https://github.com/a/b/pull/1).");
    }

    @Test
    void shouldHoldBackOnlyALinkThatIsStillBeingWritten() {
        Set<String> hosts = Set.of("github.com");

        assertThat(SlackMentorPosts.streamable("Your reviews are", hosts)).isEqualTo("Your reviews are");
        assertThat(SlackMentorPosts.streamable("Read [this guide](https://evil.exa", hosts))
                .isEqualTo("Read ");
        assertThat(SlackMentorPosts.streamable("Read https://evil.example/x and more", hosts))
                .isEqualTo("Read ");
        assertThat(SlackMentorPosts.streamable("Open ht", hosts)).isEqualTo("Open ");
        assertThat(SlackMentorPosts.streamable("Look ![", hosts)).isEqualTo("Look ");
        assertThat(SlackMentorPosts.streamable("See https://evil.example/a.\n\nNext we", hosts))
                .isEqualTo("See `https://evil.example/a`.\n\nNext we");
    }

    @Test
    void shouldStreamTheStartOfTheFinishedReply() {
        Set<String> hosts = Set.of("github.com");
        String answer = "See https://evil.example/a.\n\nThen [the PR](https://github.com/a/b/pull/1) and <!here>.";
        String finished = SlackMentorPosts.reply(List.of(heph(answer)), hosts).text();

        for (int end = 0; end <= answer.length(); end++) {
            assertThat(finished).startsWith(SlackMentorPosts.streamable(answer.substring(0, end), hosts));
        }
    }

    @Test
    void shouldReadTheJoinedReplyAsOneTextWhenAMessageClosesAnotherMessagesFence() {
        SlackMentorPosts.Reply reply = SlackMentorPosts.reply(
                List.of(heph("```"), heph("```\n[x](https://evil.example/?q=s)")), Set.of("github.com"));

        assertThat(reply.text()).doesNotContain("](https://evil.example").contains("`https://evil.example/?q=s`");
    }

    @Test
    void shouldSayOnlyOnTheFirstAnswerOfAConversationThatAnAiModelAnswers() {
        UUID answerId = UUID.randomUUID();
        var label = SlackMentorPosts.aiLabel().getFirst();
        var buttons = SlackMentorPosts.feedbackButtons(answerId).getFirst();

        assertThat(SlackMentorPosts.answerFooter(answerId, true, true)).containsExactly(label, buttons);
        assertThat(SlackMentorPosts.answerFooter(answerId, false, true)).containsExactly(buttons);
        assertThat(SlackMentorPosts.answerFooter(answerId, true, false)).containsExactly(label);
        assertThat(SlackMentorPosts.answerFooter(answerId, false, false)).isEmpty();
        assertThat(label.toString()).contains("Hephaestus answers with an AI model and can be wrong.");
    }
}

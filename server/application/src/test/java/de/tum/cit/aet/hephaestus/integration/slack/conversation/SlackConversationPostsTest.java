package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.Message;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDTO;
import de.tum.cit.aet.hephaestus.conversation.ConversationLinkDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlackConversationPostsTest extends BaseUnitTest {

    @Test
    void shouldRecognizeAPostByItsMetadataWhenTheHistoryReturnsIt() {
        UUID entryId = UUID.randomUUID();
        Message tagged = new Message();
        tagged.setMetadata(SlackConversationPosts.metadata(entryId));
        Message other = new Message();
        other.setMetadata(new Message.Metadata(
                "heph_entry", Map.of("entry_id", UUID.randomUUID().toString())));

        Message untagged = new Message();
        untagged.setBotId("B1");

        assertThat(SlackConversationPosts.isPostOf(tagged, entryId)).isTrue();
        assertThat(SlackConversationPosts.isPostOf(other, entryId)).isFalse();
        assertThat(SlackConversationPosts.isPostOf(untagged, entryId)).isFalse();
    }

    @Test
    void shouldReadTheVoteAndTheAnswerWhenAFeedbackButtonIsPressed() {
        UUID answerId = UUID.randomUUID();

        assertThat(SlackConversationPosts.feedback("NOT_HELPFUL:" + answerId))
                .contains(new SlackConversationPosts.Feedback(answerId, ConversationEntry.Vote.NOT_HELPFUL));
        assertThat(SlackConversationPosts.feedback("positive_feedback")).isEmpty();
        assertThat(SlackConversationPosts.feedback("MAYBE:" + answerId)).isEmpty();
    }

    @Test
    void shouldShowTheStartOfTheAnswerAsPlainTextWhenSlackNotifies() {
        assertThat(SlackConversationPosts.notification("## Reviews\n\nYour reviews are **quick**."))
                .isEqualTo("Reviews Your reviews are quick.");
        assertThat(SlackConversationPosts.notification("word ".repeat(100)))
                .endsWith("…")
                .hasSizeLessThan(290);
    }

    @Test
    void shouldCutTheMarkdownWithANoteWhenTheAnswerIsLongerThanSlackShows() {
        String shown =
                SlackConversationPosts.markdown("a".repeat(20_000)).getFirst().toString();

        assertThat(shown).contains("The full answer is in Hephaestus.");
    }

    private static ConversationEntryDTO hephaestus(String text, ConversationLinkDTO... links) {
        return new ConversationEntryDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                1,
                ConversationEntryDTO.Kind.ANSWER,
                ConversationEntry.Surface.SLACK,
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
        ConversationLinkDTO feedback = new ConversationLinkDTO(
                ConversationLinkDTO.Kind.FEEDBACK,
                feedbackId,
                "Your description says what changed.\nSay why, too.",
                null,
                421L);
        ConversationLinkDTO result = new ConversationLinkDTO(
                ConversationLinkDTO.Kind.RESULT,
                UUID.randomUUID(),
                "The tests cover the cache.",
                "explain-decisions",
                421L);

        SlackConversationPosts.Reply reply = SlackConversationPosts.reply(
                List.of(hephaestus("Here is what I saw.", feedback, result), hephaestus("What do you make of it?")),
                Set.of());

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
        ConversationLinkDTO first = new ConversationLinkDTO(
                ConversationLinkDTO.Kind.FEEDBACK, feedbackId, "Your description says what changed.", null, 421L);
        ConversationLinkDTO again = new ConversationLinkDTO(
                ConversationLinkDTO.Kind.FEEDBACK, feedbackId, "Say why you chose it, too.", null, 421L);
        SlackConversationPosts.Reply reply =
                SlackConversationPosts.reply(List.of(hephaestus("Here is what I saw.", first, again)), Set.of());

        // Slack shows the first quote whole and the second one only in part.
        SlackConversationPosts.Reply.Seen seen =
                reply.shownIn(reply.text().substring(0, reply.text().length() - 5));

        assertThat(seen.whole()).isEmpty();
        assertThat(seen.partly()).containsExactly(feedbackId);
    }

    @Test
    void shouldShowALinkToAnotherHostAsCodeWhenHephaestusRepliesInSlack() {
        SlackConversationPosts.Reply reply = SlackConversationPosts.reply(
                List.of(
                        hephaestus(
                                "Read [this guide](https://evil.example/?q=secret) or [the PR](https://github.com/a/b/pull/1).")),
                Set.of("github.com"));

        assertThat(reply.text())
                .isEqualTo(
                        "Read this guide (`https://evil.example/?q=secret`) or [the PR](https://github.com/a/b/pull/1).");
    }

    @Test
    void shouldReadTheJoinedReplyAsOneTextWhenAMessageClosesAnotherMessagesFence() {
        SlackConversationPosts.Reply reply = SlackConversationPosts.reply(
                List.of(hephaestus("```"), hephaestus("```\n[x](https://evil.example/?q=s)")), Set.of("github.com"));

        assertThat(reply.text()).doesNotContain("](https://evil.example").contains("`https://evil.example/?q=s`");
    }

    @Test
    void shouldSayOnlyOnTheFirstAnswerOfAConversationThatAnAiModelAnswers() {
        UUID answerId = UUID.randomUUID();
        var label = SlackConversationPosts.aiLabel().getFirst();
        var buttons = SlackConversationPosts.feedbackButtons(answerId).getFirst();

        assertThat(SlackConversationPosts.answerFooter(answerId, true, true)).containsExactly(label, buttons);
        assertThat(SlackConversationPosts.answerFooter(answerId, false, true)).containsExactly(buttons);
        assertThat(SlackConversationPosts.answerFooter(answerId, true, false)).containsExactly(label);
        assertThat(SlackConversationPosts.answerFooter(answerId, false, false)).isEmpty();
        assertThat(label.toString()).contains("Hephaestus answers with an AI model and can be wrong.");
    }
}

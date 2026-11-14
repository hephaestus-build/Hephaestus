package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ConversationTurnProgressTest {

    private static final long WORKSPACE = 7;
    private static final UUID CONVERSATION = UUID.randomUUID();

    private final JsonMapper mapper = JsonMapper.shared();
    private final ConversationTurnProgress reader = new ConversationTurnProgress(mapper);
    private final UUID turnId = UUID.randomUUID();
    private final ConversationTurn turn = running(turnId, 1);

    @Test
    void shouldRebuildEachPartialAnswerFromChangesAfterTheFirstWholeSnapshot() {
        ConversationTurnProgress.Update first = whole(1, answer("Pin"));
        ConversationTurnProgress.Update second = change(2, 1, answer("Pin"), answer("Pin the image"));

        assertThat(deliver(send(first))).isTrue();
        assertThat(deliver(send(second))).isTrue();

        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Pin the image")));
        assertThat(decode(send(second)).text()).isEqualTo(" the image");
    }

    @Test
    void shouldShowNothingAfterAMissedFrameUntilTheNextWholeSnapshot() {
        // The whole snapshot 1 was missed.
        assertThat(deliver(send(change(2, 1, answer("Pin"), answer("Pin the")))))
                .as("a change without its base")
                .isFalse();
        assertThat(reader.snapshot(turn)).isNull();
        assertThat(deliver(send(whole(3, answer("Pin the image"))))).isTrue();
        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Pin the image")));
    }

    @Test
    void shouldCarryALargeAnswerInFramesBelowTheNotificationLimit() {
        String text = "Ein Absatz mit Umlauten äöü und 🙂. ".repeat(2_000);
        List<ConversationLedgerEvents.ProgressChanged> frames = send(whole(1, answer(text)));

        assertThat(frames).hasSizeGreaterThan(1);
        assertThat(frames)
                .allSatisfy(frame -> assertThat(mapper.writerFor(ConversationLedgerEvents.LedgerChanged.class)
                                .writeValueAsBytes(frame)
                                .length)
                        .isLessThan(8000));
        assertThat(deliver(frames)).isTrue();
        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer(text)));
    }

    @Test
    void shouldDropFramesOutOfOrderAndKeepNoAnswerOfAnotherAttempt() {
        List<ConversationLedgerEvents.ProgressChanged> frames = send(whole(1, answer("x".repeat(20_000))));
        assertThat(frames).hasSizeGreaterThan(2);

        assertThat(deliver(List.of(frames.get(0), frames.get(2), frames.get(1))))
                .isFalse();
        assertThat(reader.snapshot(turn)).isNull();
        assertThat(deliver(frames)).isTrue();
        assertThat(reader.snapshot(running(turnId, 2)))
                .as("a later attempt shows its own answer")
                .isNull();
    }

    /** One connection receives frames in commit order, so a whole snapshot is the newest whatever its sequence. */
    @Test
    void shouldTakeAWholeSnapshotWhateverItsSequence() {
        assertThat(deliver(send(whole(1_000, answer("Pin"))))).isTrue();

        assertThat(deliver(send(whole(5, answer("Pin it"))))).isTrue();

        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Pin it")));
    }

    @Test
    void shouldTakeOnlyAnUpdateThatCouldBeOneOfItsAttempts() {
        assertThat(ConversationTurnProgress.wellFormed(whole(1, answer("Pin")))).isTrue();
        assertThat(ConversationTurnProgress.wellFormed(change(2, 1, answer("Pin"), answer("Pin it"))))
                .isTrue();

        assertThat(ConversationTurnProgress.wellFormed(new ConversationTurnProgress.Update(0, 0, 0, 0, "{}")))
                .as("no sequence")
                .isFalse();
        assertThat(ConversationTurnProgress.wellFormed(new ConversationTurnProgress.Update(2, 2, 0, 0, "x")))
                .as("a base that is not before it")
                .isFalse();
        assertThat(ConversationTurnProgress.wellFormed(new ConversationTurnProgress.Update(2, 1, -1, 0, "x")))
                .as("a negative prefix")
                .isFalse();
        assertThat(ConversationTurnProgress.wellFormed(new ConversationTurnProgress.Update(2, 0, 3, 0, "x")))
                .as("a whole snapshot that keeps part of another")
                .isFalse();
        assertThat(ConversationTurnProgress.wellFormed(new ConversationTurnProgress.Update(2, 1, 0, 0, null)))
                .as("no text")
                .isFalse();
        assertThat(ConversationTurnProgress.wellFormed(
                        whole(1, answer("x".repeat(ConversationTurnProgress.MAX_SNAPSHOT_CHARS)))))
                .as("a whole snapshot that no reader would keep")
                .isFalse();
    }

    @Test
    void shouldSendNoUpdateLargerThanReadersTake() {
        String larger = "x".repeat(ConversationTurnProgress.MAX_UPDATE_BYTES + 1);

        assertThat(send(new ConversationTurnProgress.Update(2, 1, 0, 0, larger)))
                .isEmpty();
    }

    @Test
    void shouldForgetTheAnswerOnceTheTurnNoLongerRuns() {
        assertThat(deliver(send(whole(1, answer("Pin"))))).isTrue();

        reader.changed(new ConversationLedgerEvents.TurnChanged(
                WORKSPACE, CONVERSATION, turnId, ConversationTurn.Status.DONE, ConversationTurn.Surface.APP, 1));

        assertThat(reader.snapshot(turn)).isNull();
    }

    /** The end of a stopped turn, on any worker, reads what readers may have seen. */
    @Test
    void shouldKeepTheAnswerOfAStoppedTurnUntilItEnds() {
        assertThat(deliver(send(whole(1, answer("Pin"))))).isTrue();

        reader.changed(new ConversationLedgerEvents.TurnChanged(
                WORKSPACE, CONVERSATION, turnId, ConversationTurn.Status.STOPPED, ConversationTurn.Surface.APP, 1));

        assertThat(reader.snapshot(WORKSPACE, turnId, 1)).isEqualTo(mapper.readTree(answer("Pin")));
        reader.changed(new ConversationLedgerEvents.TurnChanged(
                WORKSPACE, CONVERSATION, turnId, ConversationTurn.Status.DONE, ConversationTurn.Surface.APP, 1));
        assertThat(reader.snapshot(WORKSPACE, turnId, 1)).isNull();
    }

    private List<ConversationLedgerEvents.ProgressChanged> send(ConversationTurnProgress.Update update) {
        return reader.frames(WORKSPACE, CONVERSATION, turnId, ConversationTurn.Surface.APP, 1, update);
    }

    private static ConversationTurnProgress.Update whole(long sequence, String text) {
        return new ConversationTurnProgress.Update(sequence, 0, 0, 0, text);
    }

    /** The change from {@code previous} to {@code next}, as the loop makes it. */
    private static ConversationTurnProgress.Update change(long sequence, long base, String previous, String next) {
        int prefix = 0;
        while (prefix < Math.min(previous.length(), next.length()) && previous.charAt(prefix) == next.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < Math.min(previous.length(), next.length()) - prefix
                && previous.charAt(previous.length() - 1 - suffix) == next.charAt(next.length() - 1 - suffix)) {
            suffix++;
        }
        return new ConversationTurnProgress.Update(
                sequence, base, prefix, suffix, next.substring(prefix, next.length() - suffix));
    }

    /** Whether the frames completed an update that readers learn of. */
    private boolean deliver(List<ConversationLedgerEvents.ProgressChanged> frames) {
        boolean changed = false;
        for (ConversationLedgerEvents.ProgressChanged frame : frames) {
            changed = reader.accept(frame).isPresent();
        }
        return changed;
    }

    private ConversationTurnProgress.Update decode(List<ConversationLedgerEvents.ProgressChanged> frames) {
        var bytes = new ByteArrayOutputStream();
        frames.forEach(frame -> bytes.writeBytes(Base64.getDecoder().decode(frame.data())));
        return mapper.readValue(bytes.toByteArray(), ConversationTurnProgress.Update.class);
    }

    private static String answer(String text) {
        return JsonMapper.shared()
                .createObjectNode()
                .put("activity", "writing")
                .set(
                        "messages",
                        JsonMapper.shared()
                                .createArrayNode()
                                .add(JsonMapper.shared().createObjectNode().put("text", text)))
                .toString();
    }

    private static ConversationTurn running(UUID turnId, int attempt) {
        ConversationTurn turn = mock(ConversationTurn.class);
        when(turn.getStatus()).thenReturn(ConversationTurn.Status.RUNNING);
        when(turn.getWorkspaceId()).thenReturn(WORKSPACE);
        when(turn.getId()).thenReturn(turnId);
        when(turn.getAttempt()).thenReturn(attempt);
        return turn;
    }
}

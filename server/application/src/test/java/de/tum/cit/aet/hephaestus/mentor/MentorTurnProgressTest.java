package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class MentorTurnProgressTest {

    private static final long WORKSPACE = 7;
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-10-31T20:00:00Z");

    private final JsonMapper mapper = JsonMapper.shared();
    private final MentorTurnProgress sender = new MentorTurnProgress(mapper);
    private final MentorTurnProgress reader = new MentorTurnProgress(mapper);
    private final UUID turnId = UUID.randomUUID();
    private final MentorTurn turn = running(turnId, 1);

    @Test
    void shouldRebuildEachPartialAnswerFromChangesAfterTheFirstWholeSnapshot() {
        List<MentorLedgerEvents.ProgressChanged> first = send(answer("Pin"), START);
        List<MentorLedgerEvents.ProgressChanged> second = send(answer("Pin the image"), START.plusMillis(250));

        assertThat(deliver(first)).isTrue();
        assertThat(deliver(second)).isTrue();

        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Pin the image")));
        assertThat(decode(second).baseSequence())
                .as("the second update is a change")
                .isNotZero();
        assertThat(decode(second).text().length())
                .isLessThan(answer("Pin the image").length());
    }

    @Test
    void shouldShowNothingAfterAMissedFrameUntilTheNextWholeSnapshot() {
        send(answer("Pin"), START);
        List<MentorLedgerEvents.ProgressChanged> change = send(answer("Pin the"), START.plusMillis(250));
        List<MentorLedgerEvents.ProgressChanged> whole =
                send(answer("Pin the image"), START.plus(MentorTurnProgress.Stream.FULL_EVERY));

        assertThat(deliver(change)).as("a change without its base").isFalse();
        assertThat(reader.snapshot(turn)).isNull();
        assertThat(deliver(whole)).isTrue();
        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Pin the image")));
    }

    @Test
    void shouldCarryALargeAnswerInFramesBelowTheNotificationLimit() {
        String text = "Ein Absatz mit Umlauten äöü und 🙂. ".repeat(2_000);
        List<MentorLedgerEvents.ProgressChanged> frames = send(answer(text), START);

        assertThat(frames).hasSizeGreaterThan(1);
        assertThat(frames)
                .allSatisfy(frame -> assertThat(mapper.writerFor(MentorLedgerEvents.LedgerChanged.class)
                                .writeValueAsBytes(frame)
                                .length)
                        .isLessThan(8000));
        assertThat(deliver(frames)).isTrue();
        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer(text)));
    }

    @Test
    void shouldNeverSplitASurrogatePairInAChange() {
        assertThat(deliver(send(answer("Fertig 🙂"), START))).isTrue();
        assertThat(deliver(send(answer("Fertig 🙃"), START.plusMillis(250)))).isTrue();

        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Fertig 🙃")));
    }

    @Test
    void shouldDropFramesOutOfOrderAndKeepNoAnswerOfAnotherAttempt() {
        List<MentorLedgerEvents.ProgressChanged> frames = send(answer("x".repeat(20_000)), START);
        assertThat(frames).hasSizeGreaterThan(2);

        assertThat(deliver(List.of(frames.get(0), frames.get(2), frames.get(1))))
                .isFalse();
        assertThat(reader.snapshot(turn)).isNull();
        assertThat(deliver(frames)).isTrue();
        assertThat(reader.snapshot(running(turnId, 2)))
                .as("a later attempt shows its own answer")
                .isNull();
    }

    @Test
    void shouldTakeAWholeSnapshotFromAProcessWhoseClockRunsBehind() {
        assertThat(deliver(send(answer("Pin"), START))).isTrue();
        MentorTurnProgress behind = new MentorTurnProgress(mapper);
        List<MentorLedgerEvents.ProgressChanged> whole = behind.frames(
                WORKSPACE,
                CONVERSATION,
                turnId,
                MentorTurn.Surface.APP,
                1,
                behind.next(WORKSPACE, turnId, 1, answer("Pin it"), START.minusSeconds(60))
                        .orElseThrow());

        assertThat(deliver(whole)).isTrue();
        assertThat(reader.snapshot(turn)).isEqualTo(mapper.readTree(answer("Pin it")));
    }

    @Test
    void shouldSendNoPartialAnswerThatNoReaderWouldKeep() {
        String tooLong = answer("x".repeat(MentorTurnProgress.MAX_SNAPSHOT_CHARS));

        assertThat(sender.next(WORKSPACE, turnId, 1, tooLong, START)).isEmpty();
    }

    @Test
    void shouldForgetTheAnswerOnceTheTurnNoLongerRuns() {
        assertThat(deliver(send(answer("Pin"), START))).isTrue();

        reader.changed(new MentorLedgerEvents.TurnChanged(
                WORKSPACE, CONVERSATION, turnId, MentorTurn.Status.DONE, MentorTurn.Surface.APP, 1));

        assertThat(reader.snapshot(turn)).isNull();
    }

    private List<MentorLedgerEvents.ProgressChanged> send(String text, Instant now) {
        return sender.frames(
                WORKSPACE,
                CONVERSATION,
                turnId,
                MentorTurn.Surface.APP,
                1,
                sender.next(WORKSPACE, turnId, 1, text, now).orElseThrow());
    }

    /** Whether the frames completed an update that readers learn of. */
    private boolean deliver(List<MentorLedgerEvents.ProgressChanged> frames) {
        boolean changed = false;
        for (MentorLedgerEvents.ProgressChanged frame : frames) {
            changed = reader.accept(frame).isPresent();
        }
        return changed;
    }

    private MentorTurnProgress.Update decode(List<MentorLedgerEvents.ProgressChanged> frames) {
        var bytes = new ByteArrayOutputStream();
        frames.forEach(frame -> bytes.writeBytes(Base64.getDecoder().decode(frame.data())));
        return mapper.readValue(bytes.toByteArray(), MentorTurnProgress.Update.class);
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

    private static MentorTurn running(UUID turnId, int attempt) {
        MentorTurn turn = mock(MentorTurn.class);
        when(turn.getStatus()).thenReturn(MentorTurn.Status.RUNNING);
        when(turn.getWorkspaceId()).thenReturn(WORKSPACE);
        when(turn.getId()).thenReturn(turnId);
        when(turn.getAttempt()).thenReturn(attempt);
        return turn;
    }
}

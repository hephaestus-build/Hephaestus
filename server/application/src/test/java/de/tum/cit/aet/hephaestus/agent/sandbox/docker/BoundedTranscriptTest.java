package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedTranscriptTest extends BaseUnitTest {

    @Test
    @DisplayName("a transcript that fits in the head is kept exactly as it arrived")
    void shouldKeepEveryCharacterWhenTheHeadHoldsItAll() {
        var transcript = new BoundedTranscript(64, 64);
        transcript.append("first\n");
        transcript.append("second\n");
        transcript.append("third\n");

        assertThat(transcript.toString()).isEqualTo("first\nsecond\nthird\n");
        assertThat(transcript.dropped()).isZero();
    }

    @Test
    @DisplayName("a transcript that spills into the tail without overflowing is still byte-identical")
    void shouldKeepEveryCharacterWhenTheTailHoldsTheOverflow() {
        var transcript = new BoundedTranscript(8, 32);
        String written = "0123456789abcdefghij";
        for (int i = 0; i < written.length(); i++) {
            transcript.append(String.valueOf(written.charAt(i)));
        }

        assertThat(transcript.toString()).isEqualTo(written);
        assertThat(transcript.dropped()).isZero();
    }

    @Test
    @DisplayName("a transcript too large to hold keeps both ends and says what is missing")
    void shouldKeepBothEndsAndNameTheGapWhenTheRunOutgrowsTheLimits() {
        var transcript = new BoundedTranscript(10, 10);
        transcript.append("HEAD012345");
        for (int i = 0; i < 20; i++) {
            transcript.append("middle----");
        }
        transcript.append("THE ENDING");

        String out = transcript.toString();
        assertThat(out).startsWith("HEAD012345");
        assertThat(out).endsWith("THE ENDING");
        assertThat(out).contains("200 characters of this transcript were dropped here");
        assertThat(transcript.dropped()).isEqualTo(200);
    }

    @Test
    @DisplayName("the ending survives even behind a chunk larger than the whole tail")
    void shouldKeepTheEndingWhenOneChunkExceedsTheTailLimit() {
        var transcript = new BoundedTranscript(4, 4);
        transcript.append("head");
        transcript.append("a".repeat(100));
        transcript.append("FATAL: the reason the run ended");

        assertThat(transcript.toString()).endsWith("FATAL: the reason the run ended");
        assertThat(transcript.dropped()).isEqualTo(100);
    }

    @Test
    @DisplayName("appending after a large drop still lands at the end, and the gap keeps counting")
    void shouldGoOnRecordingWhenAppendFollowsALargeDrop() {
        var transcript = new BoundedTranscript(4, 4);
        transcript.append("head");
        for (int i = 0; i < 50; i++) {
            transcript.append("dropped---");
        }
        transcript.append("");
        transcript.append("last");

        assertThat(transcript.toString()).startsWith("head").endsWith("last");
        assertThat(transcript.dropped()).isEqualTo(500);
    }

    @Test
    @DisplayName("limits that could hold nothing are refused")
    void shouldRefuseLimitsThatCannotHoldAnything() {
        assertThatThrownBy(() -> new BoundedTranscript(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedTranscript(10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

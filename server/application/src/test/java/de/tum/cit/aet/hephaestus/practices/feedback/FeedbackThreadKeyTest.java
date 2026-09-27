package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The thread key decides supersede-vs-new on re-review: two deliveries that share a key edit one comment
 * in place; two that differ post a fresh one. So determinism (same destination → same key) and
 * collision-sensitivity (any destination axis change → a different key) are correctness, not cosmetics.
 * These cases lock the key's grain — {@code (artifact, recipient, surface)} — and pin a golden vector so
 * the wire identity can never drift silently. Mirrors {@code ObservationFingerprintTest}.
 */
class FeedbackThreadKeyTest extends BaseUnitTest {

    private static final String TYPE = "scm.pull_request";

    @Test
    @DisplayName("identical destination → identical 64-char key (deterministic across runs)")
    void deterministic() {
        String a = FeedbackThreadKey.compute(TYPE, 42L, 7L, FeedbackChannel.IN_CONTEXT);
        String b = FeedbackThreadKey.compute(TYPE, 42L, 7L, FeedbackChannel.IN_CONTEXT);
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("every destination axis discriminates: artifact, recipient, surface")
    void axesDiscriminate() {
        String base = FeedbackThreadKey.compute(TYPE, 42L, 7L, FeedbackChannel.IN_CONTEXT);
        assertThat(FeedbackThreadKey.compute("scm.issue", 42L, 7L, FeedbackChannel.IN_CONTEXT))
                .as("a different artifact type is a different thread")
                .isNotEqualTo(base);
        assertThat(FeedbackThreadKey.compute(TYPE, 99L, 7L, FeedbackChannel.IN_CONTEXT))
                .as("a different artifact id is a different thread")
                .isNotEqualTo(base);
        assertThat(FeedbackThreadKey.compute(TYPE, 42L, 8L, FeedbackChannel.IN_CONTEXT))
                .as("a different recipient is a different thread (two authors never collapse)")
                .isNotEqualTo(base);
        assertThat(FeedbackThreadKey.compute(TYPE, 42L, 7L, FeedbackChannel.IN_CHAT))
                .as("a different surface is a different thread (in-context vs conversation)")
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("a non-artifact-anchored unit (null artifact) is stable and keyed by recipient + surface")
    void nullArtifactStable() {
        String n1 = FeedbackThreadKey.compute("", null, 7L, FeedbackChannel.IN_APP);
        String n2 = FeedbackThreadKey.compute("", null, 7L, FeedbackChannel.IN_APP);
        assertThat(n1).isEqualTo(n2).hasSize(64);
        assertThat(n1)
                .as("an in-app digest is distinct from an in-context unit")
                .isNotEqualTo(FeedbackThreadKey.compute("", null, 7L, FeedbackChannel.IN_CONTEXT));
    }

    /**
     * The longitudinal lanes key on the practice rather than on a piece of work, because a card about one
     * practice must replace the previous card about that practice and stand beside a card about another. These
     * cases pin that grain — {@code (practice, recipient, surface)} — and, as importantly, that it lives
     * in the same digest as the artifact-keyed lane, since a supersession lookup is one predicate on one
     * indexed column and two vocabularies would make it unwritable.
     */
    @Nested
    @DisplayName("Longitudinal lanes (keyed by practice, not by artifact)")
    class ByPractice {

        @Test
        @DisplayName("same practice, same person, same surface → one thread across runs")
        void deterministic() {
            String a = FeedbackThreadKey.forPractice("ships-tests", 7L, FeedbackChannel.IN_APP);
            String b = FeedbackThreadKey.forPractice("ships-tests", 7L, FeedbackChannel.IN_APP);
            assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("every axis discriminates: practice, recipient, surface")
        void axesDiscriminate() {
            String base = FeedbackThreadKey.forPractice("ships-tests", 7L, FeedbackChannel.IN_APP);
            assertThat(FeedbackThreadKey.forPractice("small-changes", 7L, FeedbackChannel.IN_APP))
                    .as("a card about another practice stands beside this one, it does not replace it")
                    .isNotEqualTo(base);
            assertThat(FeedbackThreadKey.forPractice("ships-tests", 8L, FeedbackChannel.IN_APP))
                    .as("two people's pages never collapse onto one thread")
                    .isNotEqualTo(base);
            assertThat(FeedbackThreadKey.forPractice("ships-tests", 7L, FeedbackChannel.IN_CHAT))
                    .as("a queued mentor unit is not the card on the page")
                    .isNotEqualTo(base);
        }

        /**
         * The two lanes share a digest without sharing a thread, and the separation has to be structural
         * rather than a bet that no practice is ever slugged like an artifact id. This case is the bet: a
         * practice named {@code "42"} against artifact 42, which collided until the practice key took a scope
         * of its own.
         */
        @Test
        @DisplayName("a practice thread never collides with an artifact thread, whatever the practice is called")
        void neverCollidesWithAnArtifactThread() {
            assertThat(FeedbackThreadKey.forPractice("42", 7L, FeedbackChannel.IN_APP))
                    .as("a practice slugged like an id is still not that artifact")
                    .isNotEqualTo(FeedbackThreadKey.compute("", 42L, 7L, FeedbackChannel.IN_APP));
            assertThat(FeedbackThreadKey.forPractice(TYPE, 7L, FeedbackChannel.IN_APP))
                    .as("a practice slugged like an artifact kind is still not that artifact")
                    .isNotEqualTo(FeedbackThreadKey.compute(TYPE, null, 7L, FeedbackChannel.IN_APP));
        }

        /**
         * A blank slug would key every one of a person's practices onto one thread, so the first card
         * written would be retired by the next card about anything at all — refused rather than hashed.
         */
        @Test
        @DisplayName("a blank practice is refused rather than collapsing every practice onto one thread")
        void blankPracticeIsRefused() {
            assertThatThrownBy(() -> FeedbackThreadKey.forPractice(" ", 7L, FeedbackChannel.IN_APP))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("golden vector: the practice digest is pinned so a queued card stays findable")
        void goldenVector() {
            assertThat(FeedbackThreadKey.forPractice("ships-tests", 7L, FeedbackChannel.IN_APP))
                    .isEqualTo("214edb3230788fc4d0a5147bffff8a4964ffa5f2320c6c7a867d0e351c149b0c");
        }
    }

    @Test
    @DisplayName("golden vectors: the canonical digest is pinned so supersession identity never drifts")
    void goldenVectors() {
        // The key digests the artifact kind, so re-spelling a kind orphans every thread opened under the
        // old spelling. The second vector names no kind, which is what isolates that axis.
        assertThat(FeedbackThreadKey.compute(TYPE, 42L, 7L, FeedbackChannel.IN_CONTEXT))
                .isEqualTo("a94dab8733d9b5e1ae7933969116e27e717b88f43e06ee63c23cf905d1b3fc96");
        // Re-pinned twice: PROFILE → REFLECTION, then REFLECTION → IN_APP. The digest takes the channel's
        // name, so each rename moved this vector.
        //
        // The first move was free — the lane had no producer yet, so no thread existed under the old
        // spelling. This one was not: the lane had shipped and written rows, and 1785743133884-57
        // deliberately renames the channel without recomputing thread_key (its comment says why). Those
        // rows are therefore orphaned for supersession — the next message about the same practice is written
        // beside the old one instead of replacing it — and the repair is to drop them, not to migrate them.
        //
        // A future rename of a channel that has shipped feedback costs the same thing again. Price it
        // before spelling it, which is why FeedbackChannel's javadoc says this is the last one.
        assertThat(FeedbackThreadKey.compute("", null, 7L, FeedbackChannel.IN_APP))
                .isEqualTo("0c836a48c322ea6d9e8da6266798b250473edc75a20113fc907d10b90429c033");
    }
}

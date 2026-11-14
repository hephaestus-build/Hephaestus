package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.slack.events.SlackConversationInputGuard.Action;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackConversationInputGuard.Verdict;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

/**
 * Obvious-abuse fast-path tests: the few unambiguous self-harm cues divert with support text, harassment cues are
 * ignored, and an ordinary coding question (even one mentioning "kill" in a technical sense) is answered normally.
 * These pin the narrow, deliberate scope of the matcher — they are NOT a claim that it detects crisis or self-harm
 * in general (paraphrase and non-English phrasing pass straight through, by construction).
 */
class KeywordSlackConversationInputGuardTest extends BaseUnitTest {

    private final KeywordSlackConversationInputGuard guard = new KeywordSlackConversationInputGuard();

    @Test
    void selfHarmCue_isDivertedWithSupportResponse() {
        Verdict verdict = guard.decide("honestly I want to die");

        assertThat(verdict.action()).isEqualTo(Action.REPLY);
        assertThat(verdict.allowsConversationTurn()).isFalse();
        assertThat(verdict.responseText()).isEqualTo(KeywordSlackConversationInputGuard.SELF_HARM_RESPONSE);
    }

    @Test
    void harassmentCue_isDeclined() {
        Verdict verdict = guard.decide("kys stupid bot");

        assertThat(verdict.action()).isEqualTo(Action.IGNORE);
        assertThat(verdict.responseText()).isNull();
    }

    @Test
    void ordinaryCodingQuestion_isSafeToAnswer() {
        Verdict verdict = guard.decide("how do I kill a zombie process in my test harness?");

        assertThat(verdict.action()).isEqualTo(Action.ALLOW);
        assertThat(verdict.allowsConversationTurn()).isTrue();
        assertThat(verdict.responseText()).isNull();
    }

    @Test
    void slackThreadQuestion_getsFixedOperationalReplyInsteadOfConversationTurn() {
        Verdict verdict = guard.decide("Reply in a chat thread");

        assertThat(verdict.action()).isEqualTo(Action.REPLY);
        assertThat(verdict.allowsConversationTurn()).isFalse();
        assertThat(verdict.responseText()).isEqualTo(KeywordSlackConversationInputGuard.THREADING_RESPONSE);
    }

    @Test
    void blankInput_isOk() {
        assertThat(guard.decide("  ").action()).isEqualTo(Action.ALLOW);
    }

    @Test
    void paraphrasedDistress_isNotCaught_documentingTheKnownLimit() {
        // By design the fast-path is a substring matcher, not crisis detection: a paraphrase with no listed cue is
        // NOT diverted (it is answered normally).
        Verdict verdict = guard.decide("I just don't want to be here anymore");

        assertThat(verdict.action()).isEqualTo(Action.ALLOW);
        assertThat(verdict.allowsConversationTurn()).isTrue();
    }
}

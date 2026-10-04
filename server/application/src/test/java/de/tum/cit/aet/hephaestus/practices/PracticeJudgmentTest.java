package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PracticeJudgmentTest extends BaseUnitTest {

    private static PracticeQuestion question(String key) {
        return new PracticeQuestion(
                key, "Title of " + key, "Is " + key + " so?", "It is so here.", "It is not so here.");
    }

    private static PracticeRule rule(
            String id, Map<String, QuestionAnswer> when, Outcome outcome, @Nullable Severity severity) {
        return new PracticeRule(id, when, outcome, severity, "The reason " + id + " gives.");
    }

    private static final PracticeJudgment WHY_IN_TITLE_OR_BODY = new PracticeJudgment(
            List.of(question("why_in_title"), question("why_in_body")),
            List.of(
                    rule(
                            "no-why",
                            Map.of("why_in_title", QuestionAnswer.NO, "why_in_body", QuestionAnswer.NO),
                            Outcome.NOT_MET,
                            Severity.MINOR),
                    rule("met", Map.of(), Outcome.MET, null)));

    @Nested
    class Validation {

        @Test
        void shouldRefuseRulesThatLeaveACombinationWithoutAnOutcome() {
            assertThatThrownBy(() -> new PracticeJudgment(
                            List.of(question("alpha")),
                            List.of(
                                    rule("first", Map.of("alpha", QuestionAnswer.YES), Outcome.MET, null),
                                    rule(
                                            "second",
                                            Map.of("alpha", QuestionAnswer.NO),
                                            Outcome.NOT_MET,
                                            Severity.MINOR))))
                    .hasMessageContaining("last rule must have no conditions");
        }

        @Test
        void shouldRefuseARuleThatCanNeverDecide() {
            assertThatThrownBy(() -> new PracticeJudgment(
                            List.of(question("alpha")),
                            List.of(
                                    rule("all", Map.of(), Outcome.MET, null),
                                    rule("never", Map.of(), Outcome.MET, null))))
                    .hasMessageContaining("never decide");
        }

        @Test
        void shouldRefuseAQuestionNoRuleUses() {
            assertThatThrownBy(() -> new PracticeJudgment(
                            List.of(question("alpha"), question("unused")),
                            List.of(
                                    rule("short", Map.of("alpha", QuestionAnswer.NO), Outcome.NOT_MET, Severity.MINOR),
                                    rule("met", Map.of(), Outcome.MET, null))))
                    .hasMessageContaining("“unused” is not used by any rule");
        }

        @Test
        void shouldRefuseARuleNamingAQuestionThePracticeDoesNotAsk() {
            assertThatThrownBy(() -> new PracticeJudgment(
                            List.of(question("alpha")),
                            List.of(
                                    rule("other", Map.of("beta", QuestionAnswer.NO), Outcome.NOT_MET, Severity.MINOR),
                                    rule("met", Map.of(), Outcome.MET, null))))
                    .hasMessageContaining("does not ask");
        }

        @Test
        void shouldRefuseARuleThatRequiresAnUndeterminedAnswer() {
            assertThatThrownBy(() ->
                            rule("open", Map.of("alpha", QuestionAnswer.UNDETERMINED), Outcome.UNDETERMINED, null))
                    .hasMessageContaining("must require YES or NO");
        }

        @Test
        void shouldRefuseASeverityOutsideNotMet() {
            assertThatThrownBy(() -> rule("met", Map.of(), Outcome.MET, Severity.MINOR))
                    .hasMessageContaining("only a Not met rule has one");
        }

        @Test
        void shouldAcceptTheHolisticJudgment() {
            assertThat(PracticeJudgment.holistic().questions()).hasSize(4);
        }
    }

    @Nested
    class Derivation {

        @Test
        void shouldDecideByTheFirstMatchingRule() {
            var derived = WHY_IN_TITLE_OR_BODY.derive(
                    Map.of("why_in_title", QuestionAnswer.NO, "why_in_body", QuestionAnswer.NO));

            assertThat(derived.outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(derived.severity()).isEqualTo(Severity.MINOR);
            assertThat(derived.decisive()).containsExactly("why_in_title", "why_in_body");
        }

        @Test
        void shouldDecideTheCatchAllFromTheAnswersThatRuledOutEachEarlierRule() {
            var derived = WHY_IN_TITLE_OR_BODY.derive(
                    Map.of("why_in_title", QuestionAnswer.YES, "why_in_body", QuestionAnswer.NO));

            assertThat(derived.outcome()).isEqualTo(Outcome.MET);
            assertThat(derived.decisive()).containsExactly("why_in_title");
            assertThat(PracticeJudgment.holistic()
                            .derive(Map.of(
                                    "has_occasion", QuestionAnswer.YES,
                                    "meets_standard", QuestionAnswer.YES,
                                    "major_shortfall", QuestionAnswer.NO,
                                    "critical_shortfall", QuestionAnswer.NO))
                            .decisive())
                    .containsExactly("has_occasion", "meets_standard");
        }

        @Test
        void shouldNameNoRuleWhenTheOutcomeAgreesThroughDifferentRules() {
            var judgment = new PracticeJudgment(
                    List.of(question("alpha"), question("beta")),
                    List.of(
                            rule("alpha-gap", Map.of("alpha", QuestionAnswer.YES), Outcome.NOT_MET, Severity.MINOR),
                            rule("beta-gap", Map.of("beta", QuestionAnswer.YES), Outcome.NOT_MET, Severity.MAJOR),
                            rule("met", Map.of(), Outcome.MET, null)));

            var derived = judgment.derive(Map.of("alpha", QuestionAnswer.UNDETERMINED, "beta", QuestionAnswer.YES));

            // Not met whichever way alpha is settled, and at least minor; but the minor rule holds only if alpha is
            // YES, so its reason is no fact about the work, and the major one overstates the open way.
            assertThat(derived.outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(derived.severity()).isEqualTo(Severity.MINOR);
            assertThat(derived.rule()).isNull();
            assertThat(derived.decisive()).containsExactly("beta");
            assertThat(PracticeJudgment.unsettledHeadline(derived.outcome()))
                    .isEqualTo("The work falls short of the practice however the open questions are settled.");
        }

        @Test
        void shouldNameTheRuleThatDecidesEveryWayOfSettlingAnOpenAnswer() {
            var judgment = new PracticeJudgment(
                    List.of(question("alpha"), question("beta")),
                    List.of(
                            rule("beta-gap", Map.of("beta", QuestionAnswer.YES), Outcome.NOT_MET, Severity.MINOR),
                            rule("alpha-gap", Map.of("alpha", QuestionAnswer.YES), Outcome.NOT_MET, Severity.MAJOR),
                            rule("met", Map.of(), Outcome.MET, null)));

            var derived = judgment.derive(Map.of("alpha", QuestionAnswer.UNDETERMINED, "beta", QuestionAnswer.YES));

            assertThat(derived.rule()).isNotNull().extracting(PracticeRule::id).isEqualTo("beta-gap");
            assertThat(derived.decisive()).containsExactly("beta");
        }

        @Test
        void shouldKeepAnOutcomeAnOpenAnswerCannotChange() {
            var derived = WHY_IN_TITLE_OR_BODY.derive(
                    Map.of("why_in_title", QuestionAnswer.UNDETERMINED, "why_in_body", QuestionAnswer.YES));

            assertThat(derived.outcome()).isEqualTo(Outcome.MET);
        }

        @Test
        void shouldLeaveTheOutcomeOpenWhenAnOpenAnswerIsMaterial() {
            var derived = WHY_IN_TITLE_OR_BODY.derive(
                    Map.of("why_in_title", QuestionAnswer.UNDETERMINED, "why_in_body", QuestionAnswer.NO));

            assertThat(derived.outcome()).isEqualTo(Outcome.UNDETERMINED);
            assertThat(derived.rule()).isNull();
            assertThat(derived.decisive()).containsExactly("why_in_title");
        }

        @Test
        void shouldRefuseAMissingAnswerThatCouldStillChangeTheOutcome() {
            // With the title silent, the body decides between met and not met: it must be answered.
            assertThatThrownBy(() -> WHY_IN_TITLE_OR_BODY.derive(Map.of("why_in_title", QuestionAnswer.NO)))
                    .hasMessageContaining("missing [why_in_body]");
            assertThatThrownBy(() -> WHY_IN_TITLE_OR_BODY.derive(Map.of(
                            "why_in_title", QuestionAnswer.YES,
                            "why_in_body", QuestionAnswer.NO,
                            "why_elsewhere", QuestionAnswer.NO)))
                    .hasMessageContaining("unknown [why_elsewhere]");
        }

        @Test
        void shouldSkipAQuestionAnotherAnswerMakesMoot() {
            // A title that says why already rules out the only shortfall, so the body cannot change anything.
            assertThat(WHY_IN_TITLE_OR_BODY.skipConditions())
                    .containsEntry(
                            "why_in_body",
                            List.of(new PracticeJudgment.SkipCondition("why_in_title", QuestionAnswer.YES)))
                    .containsEntry(
                            "why_in_title",
                            List.of(new PracticeJudgment.SkipCondition("why_in_body", QuestionAnswer.YES)));

            var derived = WHY_IN_TITLE_OR_BODY.derive(Map.of("why_in_title", QuestionAnswer.YES));

            assertThat(derived.outcome()).isEqualTo(Outcome.MET);
            assertThat(derived.rule()).isNotNull().extracting(PracticeRule::id).isEqualTo("met");
            assertThat(derived.decisive()).containsExactly("why_in_title");
        }

        @Test
        void shouldReadALeftOutSeverityQuestionAsOpenAndKeepTheLowerBand() {
            // The holistic judgment's escalations change only the severity of a shortfall, never the outcome.
            PracticeJudgment holistic = PracticeJudgment.holistic();
            assertThat(holistic.gradesSeverityOnly()).containsExactly("major_shortfall", "critical_shortfall");

            var derived = holistic.derive(Map.of(
                    "has_occasion", QuestionAnswer.YES,
                    "meets_standard", QuestionAnswer.NO));

            assertThat(derived.outcome()).isEqualTo(Outcome.NOT_MET);
            assertThat(derived.severity()).isEqualTo(Severity.MINOR);
            // A question that can change the outcome is still refused when left out.
            assertThatThrownBy(() -> holistic.derive(Map.of("has_occasion", QuestionAnswer.YES)))
                    .hasMessageContaining("missing [meets_standard]");
        }

        @Test
        void shouldLeaveTheHolisticGateItsOwnQuestionsMoot() {
            var skips = PracticeJudgment.holistic().skipConditions();

            assertThat(skips.get("has_occasion")).isEmpty();
            assertThat(skips.get("meets_standard"))
                    .contains(new PracticeJudgment.SkipCondition("has_occasion", QuestionAnswer.NO));
            assertThat(skips.get("critical_shortfall"))
                    .contains(
                            new PracticeJudgment.SkipCondition("has_occasion", QuestionAnswer.NO),
                            new PracticeJudgment.SkipCondition("meets_standard", QuestionAnswer.YES));
            assertThat(PracticeJudgment.holistic()
                            .derive(Map.of("has_occasion", QuestionAnswer.NO))
                            .outcome())
                    .isEqualTo(Outcome.NOT_APPLICABLE);
        }

        @Test
        void shouldDeriveTheHolisticJudgmentLikeTheCriteriaItRestates() {
            var holistic = PracticeJudgment.holistic();

            assertThat(holistic.derive(Map.of(
                                    "has_occasion", QuestionAnswer.YES,
                                    "meets_standard", QuestionAnswer.NO,
                                    "major_shortfall", QuestionAnswer.YES,
                                    "critical_shortfall", QuestionAnswer.NO))
                            .severity())
                    .isEqualTo(Severity.MAJOR);
            assertThat(holistic.derive(Map.of(
                                    "has_occasion", QuestionAnswer.NO,
                                    "meets_standard", QuestionAnswer.NO,
                                    "major_shortfall", QuestionAnswer.NO,
                                    "critical_shortfall", QuestionAnswer.NO))
                            .outcome())
                    .isEqualTo(Outcome.NOT_APPLICABLE);
        }
    }
}

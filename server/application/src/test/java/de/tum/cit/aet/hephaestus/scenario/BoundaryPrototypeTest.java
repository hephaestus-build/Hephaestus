package de.tum.cit.aet.hephaestus.scenario;

import static de.tum.cit.aet.hephaestus.scenario.BoundaryPrototype.ALEX_ERASED;
import static de.tum.cit.aet.hephaestus.scenario.BoundaryPrototype.ALEX_SUMMARY;
import static de.tum.cit.aet.hephaestus.scenario.BoundaryPrototype.ALEX_TRAVELLING;
import static de.tum.cit.aet.hephaestus.scenario.BoundaryPrototype.JORDAN_PRIVATE;
import static de.tum.cit.aet.hephaestus.scenario.BoundaryPrototype.TEAM_A_NOTE;
import static de.tum.cit.aet.hephaestus.scenario.BoundaryPrototype.TEAM_B_NOTE;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.ALEX;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.ALEX_SPACE;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.JORDAN;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.SLACK_A;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.TEAM_A;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.TEAM_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.scenario.PrototypeSubject.Fault;
import de.tum.cit.aet.hephaestus.scenario.ScenarioReport.Kind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BoundaryPrototypeTest extends BaseUnitTest {

    @Test
    void shouldLeakNoCanaryAndReadOrSendNothingWithoutAuthorityWhenTheSubjectKeepsTheRules() {
        Harness.Result result = run(PrototypeSubject.keepingTheRules());

        result.report().assertClean();
        assertThat(result.report().attempts()).isPositive();
    }

    @Test
    void shouldShowEachRecordToEveryoneWhoMayReadItWhenTheSubjectKeepsTheRules() {
        Harness.Result result = run(PrototypeSubject.keepingTheRules());

        assertThat(entryAt(result, BoundaryPrototype.ALEX_ASKS_IN_TEAM_B, ALEX, TEAM_B))
                .contains(TEAM_B_NOTE.canary());
        assertThat(effectTo(result.transcript(), new Destination.SlackDm(ALEX, SLACK_A)))
                .contains(TEAM_A_NOTE.canary(), ALEX_TRAVELLING.canary());
        assertThat(entryAt(result, BoundaryPrototype.ALEX_ASKS_IN_PERSONAL_SPACE, ALEX, ALEX_SPACE))
                .contains(ALEX_TRAVELLING.canary(), ALEX_ERASED.canary(), ALEX_SUMMARY.canary());
        assertThat(entryAt(result, BoundaryPrototype.REVIEW_OF_JORDAN, JORDAN, TEAM_A))
                .contains(JORDAN_PRIVATE.canary());
        assertThat(effectTo(result.transcript(), new Destination.Work(TEAM_A)))
                .contains(TEAM_A_NOTE.canary())
                .doesNotContain(JORDAN_PRIVATE.canary());
    }

    @Test
    void shouldKeepAnErasedRecordAndWhatRestsOnItGoneWhenTheBackupIsRestored() {
        Harness.Result result = run(PrototypeSubject.keepingTheRules());

        assertThat(entryAt(result, BoundaryPrototype.ALEX_ASKS_AFTER_RESTORE, ALEX, ALEX_SPACE))
                .contains(ALEX_TRAVELLING.canary())
                .doesNotContain(ALEX_ERASED.canary(), ALEX_SUMMARY.canary());
    }

    @Test
    void shouldPauseWhatTravelsIntoAWorkspaceWhenItsProviderChanges() {
        Harness.Result result = run(PrototypeSubject.keepingTheRules());

        assertThat(entryAt(result, BoundaryPrototype.ALEX_ASKS_AFTER_PROVIDER_CHANGE, ALEX, TEAM_A))
                .contains(TEAM_A_NOTE.canary())
                .doesNotContain(ALEX_TRAVELLING.canary());
    }

    @Test
    void shouldNeitherAnswerNorRemindAPersonWhenTheyLeftAndWereRegrantedSinceTheReminderWasQueued() {
        Harness.Result result = run(PrototypeSubject.keepingTheRules());
        Transcript transcript = result.transcript();

        Instant answerWhileAway =
                result.start().plus(BoundaryPrototype.JORDAN_ASKS_AFTER_LEAVING).plus(PrototypeSubject.MODEL_LATENCY);
        assertThat(transcript.entries()).noneMatch(entry -> entry.at().equals(answerWhileAway));
        Transcript.OutboxAction reminder = transcript.outboxActions().stream()
                .filter(action -> action.destination().equals(new Destination.Direct(JORDAN, TEAM_A)))
                .findFirst()
                .orElseThrow();
        assertThat(transcript.effects()).noneMatch(effect -> effect.actionId().equals(reminder.id()));
    }

    @Test
    void shouldFailWhenAnotherPersonsCanaryReachesAnAnswer() {
        ScenarioReport report =
                run(PrototypeSubject.with(Fault.READS_EVERYTHING_UNREPORTED)).report();

        // The subject reports and cites none of its reads, so only the canary detectors can see the leak.
        assertThat(report.of(Kind.UNAUTHORIZED_READ)).isEmpty();
        assertThatThrownBy(report::assertClean)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the canary of jordan-private reached Alex in team-b")
                .hasMessageContaining("the canary of jordan-private reached Alex in team-a");
    }

    static Stream<Arguments> faults() {
        return Stream.of(
                Arguments.of(
                        Fault.RESTORE_WITHOUT_JOURNAL,
                        Kind.CANARY_LEAK,
                        "the canary of alex-erased reached Alex in Alex's personal space"),
                Arguments.of(
                        Fault.ERASURE_WITHOUT_CASCADE,
                        Kind.CANARY_LEAK,
                        "the canary of alex-summary reached Alex in Alex's personal space"),
                Arguments.of(Fault.NO_RECHECK_AT_SEND, Kind.UNAUTHORIZED_SEND, "the authority of Jordan in team-a"),
                Arguments.of(Fault.IGNORES_LEAVE, Kind.UNAUTHORIZED_READ, "read team-a-note for Jordan in team-a"),
                Arguments.of(
                        Fault.SLACK_TO_LAST_WORKSPACE,
                        Kind.CANARY_LEAK,
                        "the canary of team-b-note reached Alex in team-a"),
                Arguments.of(
                        Fault.SLACK_TO_LAST_WORKSPACE, Kind.UNAUTHORIZED_READ, "cites team-b-note to Alex in team-a"),
                Arguments.of(
                        Fault.IGNORES_PROVIDER_CHANGE,
                        Kind.UNAUTHORIZED_READ,
                        "read alex-travelling for Alex in team-a"));
    }

    @ParameterizedTest
    @MethodSource("faults")
    void shouldReportTheBrokenRuleWhenTheSubjectBreaksIt(Fault fault, Kind kind, String finding) {
        ScenarioReport report = run(PrototypeSubject.with(fault)).report();

        assertThat(report.of(kind))
                .anySatisfy(violation -> assertThat(violation.detail()).contains(finding));
    }

    @Test
    void shouldFailAParaphrasedLeakWhenTheModelNeverRepeatsTheCanary() {
        ScriptedModel paraphrasing = request -> "A summary in other words.";

        ScenarioReport report = Harness.run(
                        BoundaryPrototype.scenario(),
                        PrototypeSubject.with(Fault.SLACK_TO_LAST_WORKSPACE),
                        paraphrasing)
                .report();

        assertThat(report.of(Kind.CANARY_LEAK))
                .anySatisfy(violation -> assertThat(violation.detail())
                        .contains("the canary of team-b-note reached the model of")
                        .contains("reached Alex in team-a"));
    }

    private static Harness.Result run(PrototypeSubject subject) {
        return Harness.run(BoundaryPrototype.scenario(), subject, ScriptedModel.echo());
    }

    /** The answer that a step at {@code offset} got, once the model replied. */
    private static String entryAt(Harness.Result result, Duration offset, Person person, Place place) {
        Instant answered = result.start().plus(offset).plus(PrototypeSubject.MODEL_LATENCY);
        List<String> texts = result.transcript().entries().stream()
                .filter(entry -> entry.at().equals(answered) && entry.to().equals(new Reader(person, place)))
                .map(Transcript.Entry::text)
                .toList();
        assertThat(texts).hasSize(1);
        return texts.getFirst();
    }

    private static String effectTo(Transcript transcript, Destination destination) {
        List<String> texts = transcript.effects().stream()
                .filter(effect -> effect.destination().equals(destination))
                .map(Transcript.Effect::text)
                .toList();
        assertThat(texts).hasSize(1);
        return texts.getFirst();
    }
}

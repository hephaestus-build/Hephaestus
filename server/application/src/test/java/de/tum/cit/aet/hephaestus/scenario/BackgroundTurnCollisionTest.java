package de.tum.cit.aet.hephaestus.scenario;

import static de.tum.cit.aet.hephaestus.scenario.Fixture.ALEX;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.TEAM_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A scripted collision: a person writes while the background run of their finished review still waits for the model.
 * The message cancels the background run, which then writes nothing. The copy on the work is not the person's turn, so
 * it still goes out.
 */
class BackgroundTurnCollisionTest extends BaseUnitTest {

    private static final Scenario MESSAGE_DURING_BACKGROUND_RUN = Scenario.named("message during a background run")
            .at(Duration.ofMinutes(1), new Step.ReviewFinished(ALEX, TEAM_A, "pull request 7"))
            .at(Duration.ofMinutes(1).plusSeconds(10), new Step.Message(ALEX, TEAM_A, "Is my pull request ready?"))
            .build();

    @Test
    void shouldCancelTheBackgroundRunWhenItsPersonWritesBeforeItAnswers() {
        Harness.Result result =
                Harness.run(MESSAGE_DURING_BACKGROUND_RUN, PrototypeSubject.keepingTheRules(), ScriptedModel.echo());
        Transcript transcript = result.transcript();

        result.report().assertClean();
        assertThat(transcript.runEnds())
                .extracting(Transcript.RunEnd::runId, Transcript.RunEnd::outcome)
                .containsExactly(
                        tuple("run-1", Run.Outcome.CANCELLED),
                        tuple("run-2", Run.Outcome.ANSWERED),
                        tuple("run-3", Run.Outcome.ANSWERED));
        assertThat(transcript.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.runId()).isEqualTo("run-3");
            assertThat(entry.text()).contains("Is my pull request ready?");
        });
        assertThat(transcript.effects())
                .singleElement()
                .satisfies(effect -> assertThat(effect.destination()).isEqualTo(new Destination.Work(TEAM_A)));
    }
}

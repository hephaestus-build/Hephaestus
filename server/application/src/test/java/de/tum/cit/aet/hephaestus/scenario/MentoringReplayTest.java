package de.tum.cit.aet.hephaestus.scenario;

import static de.tum.cit.aet.hephaestus.scenario.Fixture.ALEX;
import static de.tum.cit.aet.hephaestus.scenario.Fixture.TEAM_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A recorded scenario replays exactly from its cassette, with no model. */
class MentoringReplayTest extends BaseUnitTest {

    @TempDir
    Path directory;

    @Test
    void shouldReplayEntriesEffectsAndOutboxActionsExactlyWhenNoModelIsAvailable() throws IOException {
        Cassette recording = Cassette.recording(ScriptedModel.echo());
        Transcript recorded = Harness.run(BoundaryPrototype.scenario(), PrototypeSubject.keepingTheRules(), recording)
                .transcript();
        Path file = directory.resolve("boundary-prototype.json");
        recording.write(file);

        Transcript replayed = Harness.run(
                        BoundaryPrototype.scenario(), PrototypeSubject.keepingTheRules(), Cassette.replaying(file))
                .transcript();

        assertThat(recorded.entries()).isNotEmpty();
        assertThat(recorded.effects()).isNotEmpty();
        assertThat(replayed.entries()).isEqualTo(recorded.entries());
        assertThat(replayed.effects()).isEqualTo(recorded.effects());
        assertThat(replayed.outboxActions()).isEqualTo(recorded.outboxActions());
        assertThat(replayed.modelCalls()).isEqualTo(recorded.modelCalls());
    }

    @Test
    void shouldFailWithTheRequestHashWhenAReplayMeetsAnUnrecordedRequest() throws IOException {
        Cassette recording = Cassette.recording(ScriptedModel.echo());
        Harness.run(BoundaryPrototype.scenario(), PrototypeSubject.keepingTheRules(), recording);
        Path file = directory.resolve("boundary-prototype.json");
        recording.write(file);
        Scenario changed = Scenario.named("changed")
                .at(Duration.ofMinutes(1), new Step.Message(ALEX, TEAM_A, "A question nobody recorded"))
                .build();

        Cassette replay = Cassette.replaying(file);
        assertThatThrownBy(() -> Harness.run(changed, PrototypeSubject.keepingTheRules(), replay))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No recorded call for request")
                .hasMessageContaining("A question nobody recorded");
    }

    @Test
    void shouldKeyTwoRequestsAlikeWhenOnlyKeyOrderAndSpacingDiffer() {
        String compact = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String reordered = "{ \"messages\" : [ { \"content\" : \"hi\", \"role\" : \"user\" } ],\n \"model\" : \"m\" }";

        assertThat(CanonicalRequestHash.of(reordered)).isEqualTo(CanonicalRequestHash.of(compact));
    }

    @Test
    void shouldKeyTwoRequestsApartWhenAValueOrTheArrayOrderDiffers() {
        String request = "{\"messages\":[{\"content\":\"a\"},{\"content\":\"b\"}],\"model\":\"m\"}";
        String otherModel = "{\"messages\":[{\"content\":\"a\"},{\"content\":\"b\"}],\"model\":\"n\"}";
        String otherOrder = "{\"messages\":[{\"content\":\"b\"},{\"content\":\"a\"}],\"model\":\"m\"}";

        assertThat(CanonicalRequestHash.of(otherModel)).isNotEqualTo(CanonicalRequestHash.of(request));
        assertThat(CanonicalRequestHash.of(otherOrder)).isNotEqualTo(CanonicalRequestHash.of(request));
    }
}

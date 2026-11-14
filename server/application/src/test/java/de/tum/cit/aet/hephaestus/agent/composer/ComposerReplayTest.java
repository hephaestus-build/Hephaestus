package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each replay case admits the packages it records: the composer loop, given the same sealed basis and the same model
 * answers, prepares the same feedback, withholds the same destinations and records the same stops. The Node loop of a
 * {@code review_compose} run replays every case over a loopback gateway to the server side. A recorded case
 * replays a live model's answers, so its outcome is exact. With {@code UPDATE_COMPOSITION_REPLAYS=1}, a case whose
 * outcome differs is rewritten from what the loop admits and fails; review the diff and run again without it.
 */
@Tag("unit")
class ComposerReplayTest {
    private static final boolean UPDATE = "1".equals(System.getenv("UPDATE_COMPOSITION_REPLAYS"));

    static List<String> cases() throws IOException {
        return ComposerReplay.names();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldAdmitTheRecordedPackagesWhenTheComposerReplaysTheModelsAnswers(String name) throws Exception {
        var replay = ComposerReplay.load(name);
        var outcome =
                ComposerReplay.node(replay, ComposerReplay.replayed(replay)).toJson();
        if (UPDATE && !replay.path("expected").equals(outcome)) {
            replay.set("expected", outcome);
            ComposerReplay.write(name, replay);
            // A Gradle daemon can keep the variable after the build that set it, so a rewrite never passes.
            fail(name + " was rewritten: review the diff, then run again without UPDATE_COMPOSITION_REPLAYS");
        }
        assertThat(replay.path("expected")).as(name).isEqualTo(outcome);
    }
}

package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each replay case admits the packages it records: the composer loop, given the same sealed basis and the same model
 * answers, prepares the same feedback, withholds the same destinations and records the same stops. The worker's loop and
 * the Node loop of a {@code review_compose} run both replay every case, the Node loop over a loopback gateway. A recorded case
 * replays a live model's answers, so its outcome is exact. Run with {@code UPDATE_COMPOSITION_REPLAYS=1} to rewrite
 * each case's expected outcome from what the loop admits, then review the diff.
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
                ComposerReplay.jvm(replay, ComposerReplay.replayed(replay)).toJson();
        if (UPDATE) {
            replay.set("expected", outcome);
            ComposerReplay.write(name, replay);
        }
        assertThat(replay.path("expected")).as(name).isEqualTo(outcome);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldAdmitTheSamePackagesWhenTheNodeComposerReplaysTheModelsAnswers(String name) throws Exception {
        var replay = ComposerReplay.load(name);

        var outcome =
                ComposerReplay.node(replay, ComposerReplay.replayed(replay)).toJson();

        assertThat(outcome).as(name).isEqualTo(replay.path("expected"));
    }
}

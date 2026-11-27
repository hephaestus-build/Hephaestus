package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotIndex;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.SnapshotRefusedException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SnapshotIndexerContractTest extends BaseUnitTest {

    static Stream<Captures.Capture> captures() {
        return Stream.of(Captures.board(), Captures.model());
    }

    @ParameterizedTest
    @MethodSource("captures")
    void shouldLetEachCitedLineNameExactlyOneElementOfItsVersion(Captures.Capture capture) {
        byte[] submitted = capture.version1().getBytes(StandardCharsets.UTF_8);

        var index = capture.indexer().index(submitted);

        assertThat(capture.cited()).hasSize(2);
        for (var cited : capture.cited()) {
            assertThat(index.elements().stream().filter(e -> e.text().contains(cited.quote())))
                    .extracting(SnapshotIndex.Element::id)
                    .containsExactly(cited.elementId());
        }
        assertThat(capture.indexer().index(submitted)).isEqualTo(index);
    }

    @ParameterizedTest
    @MethodSource("captures")
    void shouldRefuseAnotherToolsDocumentWithACodeAlone(Captures.Capture capture) {
        var foreign = "{\"format\":\"someone-else\",\"secret\":\"CONTENT\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> capture.indexer().index(foreign))
                .isInstanceOfSatisfying(SnapshotRefusedException.class, refused -> assertThat(refused.refusal())
                        .isEqualTo(SnapshotRefusal.UNSUPPORTED_VERSION))
                .hasMessage("UNSUPPORTED_VERSION");
    }

    @Test
    void shouldRefuseAnIndexWhoseLinesOrIdsCannotBeCited() {
        var cell = new SnapshotIndex.Element("a", "board.cell", "A", null, List.of("one"));
        var orphan = new SnapshotIndex.Element("b", "board.cell", "B", "gone", List.of("two"));

        assertThatThrownBy(() -> new SnapshotIndex(List.of(cell, cell), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnapshotIndex(List.of(cell, orphan), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnapshotIndex.Element("c", "board.cell", "C", null, List.of("two\nlines")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnapshotIndex.Element("d", "board.cell", "D", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

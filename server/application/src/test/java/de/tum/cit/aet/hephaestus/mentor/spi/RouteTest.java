package de.tum.cit.aet.hephaestus.mentor.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RouteTest {
    private static final Route TOP_LEVEL =
            new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000100", 7L, 11L, 42L, "U1");

    @Test
    void shouldFingerprintEqualRoutesAlike() {
        var same = new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000100", 7L, 11L, 42L, "U1");

        assertThat(same.fingerprint()).isEqualTo(TOP_LEVEL.fingerprint()).hasSize(64);
    }

    @Test
    void shouldFingerprintEveryFieldSoAnObservationOfOneRouteProvesNoOther() {
        var others = List.of(
                new Route(Transport.APP, "T1", "D1", null, "1700000000.000100", 7L, 11L, 42L, "U1"),
                new Route(Transport.SLACK, "T2", "D1", null, "1700000000.000100", 7L, 11L, 42L, "U1"),
                new Route(Transport.SLACK, "T1", "D2", null, "1700000000.000100", 7L, 11L, 42L, "U1"),
                new Route(Transport.SLACK, "T1", "D1", "1700000000.000100", "1700000000.000100", 7L, 11L, 42L, "U1"),
                new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000200", 7L, 11L, 42L, "U1"),
                new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000100", 8L, 11L, 42L, "U1"),
                new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000100", 7L, 12L, 42L, "U1"),
                new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000100", 7L, 11L, 43L, "U1"),
                new Route(Transport.SLACK, "T1", "D1", null, "1700000000.000100", 7L, 11L, 42L, "U2"));

        assertThat(others)
                .extracting(Route::fingerprint)
                .doesNotHaveDuplicates()
                .doesNotContain(TOP_LEVEL.fingerprint());
    }

    @Test
    void shouldTellAnAbsentParentFromAnEmptyOneAndKeepFieldsApart() {
        var emptyParent = new Route(Transport.SLACK, "T1", "D1", "", "1700000000.000100", 7L, 11L, 42L, "U1");
        var shifted = new Route(Transport.SLACK, "T1D", "1", null, "1700000000.000100", 7L, 11L, 42L, "U1");

        assertThat(emptyParent.fingerprint()).isNotEqualTo(TOP_LEVEL.fingerprint());
        assertThat(shifted.fingerprint()).isNotEqualTo(TOP_LEVEL.fingerprint());
    }

    @Test
    void shouldEndARequestWithOnlyAnAnswerAContinuationOrAFailure() {
        assertThat(List.of(EffectKind.values()))
                .filteredOn(EffectKind::terminal)
                .containsExactly(EffectKind.ANSWER, EffectKind.CONTINUATION, EffectKind.TERMINAL_FAILURE);
    }
}
